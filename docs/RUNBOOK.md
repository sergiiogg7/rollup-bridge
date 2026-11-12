# Runbook

How to operate the Java services against the contracts, and what to do when something is stuck. Educational project: the "production" here is the local demo, but the procedures are the real ones. Each entry names the test or script that exercises the situation; a procedure without proof is marked as such.

Conventions: `JAR=services/app/target/rollup-bridge.jar`, `java --enable-native-access=ALL-UNNAMED -jar $JAR <command>`. Configuration is environment variables only (see `.env.example`). State lives in SQLite files under `DB_DIR` (one per service). Metrics: `http://127.0.0.1:$STATUS_PORT/metrics` ([OBSERVABILITY.md](OBSERVABILITY.md)); the plain-text state: `status` command or `http://127.0.0.1:$STATUS_PORT/` for `all`.

## 1. Start and stop

| Task | How | Proven by |
|---|---|---|
| Whole local stack | `scripts/demo.sh` (`KEEP=1` leaves it running) | the demo, [DEMO.md](DEMO.md) |
| One role per process | `... deposit-relayer`, `proposer`, `withdrawal-relayer`, `challenger`, `guardian watch`; give each its own `STATUS_PORT` and key | `scripts/observability-drill.sh` (challenger as its own process) |
| Everything but some roles | `DISABLE_SERVICES=challenger java ... all` | the same drill |
| Stop | `kill <pid>` (SIGTERM). Never `kill -9` by process name. Stopping at any instant is safe: every tick reconciles with the chain first | E5, E5b, E8, `crashAfterSendBeforeCommitDoesNotDoubleMint`, `ChaosPropertyTest` (random crashes and restarts) |
| Restart | start it again with the same `DB_DIR`; nothing else to do | same tests |
| Two instances of one role by mistake | harmless: the contracts' replay guards and the stores' reconciliation make the second one a no-op | E1b, `ChaosPropertyTest` (two relayers with separate stores) |

Keys: one account per role (relayer, proposer, challenger, guardian). The relayer needs gas on both chains, the proposer and challenger need their bonds in ETH on L1 (`proposerBond()` / `challengerBond()` of the oracle).

## 2. A deposit is stuck

Symptoms: user has paid on L1 and has no bETH; `DepositStuck` alert; `bridge_deposit_oldest_pending_age_seconds` grows.

1. `status`: find the deposit and its state.
   - **Seen**: waiting for `DEPOSIT_CONFIRMATIONS` (`bridge_l1_confirmations_waiting`). Normal until the L1 has mined that many blocks. If L1 is not producing blocks, nothing to do on our side.
   - **Confirmed / Relaying**: the relayer is trying to mint. Look at `bridge_service_errors_total{service="deposit-relayer"}` and the log: `rpc_transport` means the L2 RPC is down; `tx_reverted` with `NotRelayer` means the relayer key is not the one set on `L2Bridge` (`setRelayer` by the owner); out of gas means fund the relayer on L2.
   - **Dropped**: the block that held the deposit was reorged away. If the deposit reappears in a new block the relayer takes it back to Seen by itself; if it does not, the deposit never happened on the canonical chain and there is nothing to mint.
   - **Done**: minted; check `cast call <bETH> 'balanceOf(address)' <user>` on L2.
2. `bridge_cursor_lag_blocks{chain="l1"}` large and growing (`CursorStalled`): the relayer is not scanning; check its process and the L1 RPC.
3. Never mint by hand: `L2Bridge.finalizeDeposit` is guarded by the deposit id and a manual mint of an id the relayer later processes is rejected, but a mint for a deposit that is not on the canonical L1 is an unbacked mint.

Proven by: E1 (happy path), E1b, the crash test in `DepositScenariosTest`, E6 safe vs unsafe (why the confirmation depth exists), `ChaosPropertyTest` (every canonical deposit reaches L2 exactly once, under flaky RPCs and reorgs).

**If `UnbackedMintSuspected` fires**: an already minted deposit appeared in another L1 block, so a reorg deeper than the confirmation depth happened. Pause the bridge (section 6), compare `L1Bridge.totalLocked()` with `bETH.totalSupply()` plus the burned-but-unpaid amount, and raise `DEPOSIT_CONFIRMATIONS`. There is no automatic repair; see the residual risk in [THREAT-MODEL.md](THREAT-MODEL.md). Proven only up to the detection: `ChaosPropertyTest.theHarnessDetectsTheLossThatZeroConfirmationsAllows` and the alert unit test.

## 3. A withdrawal is stuck

`status` shows the withdrawal relayer's state per withdrawal:

| State | Meaning | If it does not move |
|---|---|---|
| Initiated | seen on L2, no live output covers it yet | the **proposer** is down or reverting: `bridge_outputs_proposed_total` flat; check `bridge_service_errors_total{service="proposer"}` (`NotMonotonic` means the L2 block it wants to claim is not newer than the last live output; it resolves when L2 produces a block or the lying output is deleted) |
| Included | an output covers it | the relayer is about to prove; check its errors, `bridge_l1_paused`, and that the output is `Proposed` |
| Proven | proof accepted on L1, waiting for the window | normal until `bridge_open_output_min_window_remaining_seconds` reaches 0. Not moving after that: the output is **Challenged** (section 4), the bridge is **paused** (section 6), or an earlier output is challenged (global gate: nothing finalizes while `openChallenges > 0`) |
| Invalidated | the output (or the proof) was deleted or reorged away | the relayer re-includes and re-proves by itself once a live output covers it; if none does, the proposer must post one |
| Finalized | paid (or credited to `claimable` if the target reverts: `cast send <L1Bridge> 'claim()'` from the target) | |

A transfer to a contract target that reverts does not block anything: the amount becomes `claimable[target]` (E11).

Proven by: E2 (full withdrawal), E5/E5b (crash between prove/finalize and the store write), E7 (reorg removes the output), E9 (re-prove after deletion), E11, `ReorgResilienceTest` (reorg removes the prove or the finalize transaction), `ChaosPropertyTest`.

## 4. An output is challenged

`OutputChallenged` alert, or `status` shows CHALLENGED. Withdrawals stop finalizing until it is ruled on.

1. The guardian service rules on it by itself: it recomputes the withdrawal tree from L2 logs and sends `resolveChallenge`. Look at `bridge_challenges_resolved_total{proposer_was_wrong}`.
2. By hand: `... guardian resolve <outputIndex>` (prints whether the proposer was wrong). Use a guardian RPC endpoint that is not the challenger's.
3. The guardian must act within `RESOLUTION_PERIOD` of the challenge (600 s in the demo, 3 days by default). After that anyone can call `OutputOracle.expireChallenge(index)`: the output and every later one are deleted and every bond is refunded (fail-safe, but nobody is slashed). `cast send <OutputOracle> 'expireChallenge(uint256)' <index> --private-key <any funded key>`.
4. When the proposer was wrong, the output and every later one are deleted; the withdrawal relayer sees `Invalidated` and re-proves against the next live output; the proposer posts a fresh output.

Proven by: E3 (fake leaf caught, never pays), E3b (four kinds of lie), E3c (an honest output is not challenged), E3d (recovery after deletion), E4 (a malicious challenger loses its bond), `unresponsiveGuardianChallengeExpires`, the demo step 7-8, `scripts/observability-drill.sh` (challenger dies, comes back, lying output deleted).

**Nobody challenged and the window is closing** (`OutputWindowClosingUnchecked`, `ChallengerDown`): start a challenger (`... challenger`) at once; if no healthy challenger can be started in time and the output is wrong, the guardian can `deleteFrom(index)` (emergency, only before the window ends: `... guardian` has no subcommand for it, use `cast send <OutputOracle> 'deleteFrom(uint256)' <index>` with the guardian key). A wrong output that outlives its window finalizes: that is the liveness assumption, demonstrated on purpose by `challengerOfflineLongerThanTheWindowLetsTheAttackThrough`.

## 5. Rotate the guardian

The owner (not the guardian) calls `OutputOracle.setGuardian(newGuardian)`; `L1Bridge` reads the guardian from the oracle, so one call changes both. Then restart the guardian service with the new `GUARDIAN_KEY`. Challenges pending at the time can be ruled by the new guardian if still within `RESOLUTION_PERIOD`. The owner itself moves with `Ownable2Step` (`transferOwnership`, then `acceptOwnership` by the new owner).

Proven by: `ownerReplacesTheGuardian` (an unresponsive guardian is replaced and the challenge is ruled).

## 6. Pause and unpause

- Pause: `... guardian pause` (guardian key). Blocks `depositETH`, `proveWithdrawal` and `finalizeWithdrawal`; never blocks `claim`. The services keep running and simply wait (`bridge_l1_paused` = 1, alert `BridgePaused`); the withdrawal relayer does not send transactions while paused.
- Unpause: `... guardian unpause`. In-flight withdrawals continue; windows keep running while paused, so a long pause can leave outputs finalizable the moment it ends.
- Pause first when funds may be at risk (`UnbackedMintSuspected`, a lying output past its window), investigate second.

Proven by: E10 (pause during in-flight withdrawals, `claim` still works), `test_attack_pauseDuringInFlight...` in Foundry, `ChaosPropertyTest` (random pauses; everything settles after the unpause).

## 7. Incident table

| Alert or symptom | Likely cause | First action | Evidence it is handled |
|---|---|---|---|
| `OutputWindowClosingUnchecked` | challenger down or failing | start or fix a challenger; check `bridge_service_errors_total{service="challenger"}` | `scripts/observability-drill.sh`, E3 |
| `ChallengerDown` | challenger process dead, or every tick fails | restart it (same `DB_DIR`) | same drill, alert unit tests |
| `OutputChallenged` | an honest challenger found a bad output, or a griefing challenge | wait for the guardian; `guardian resolve <i>` | E3, E4 |
| `BridgePaused` | the guardian paused | see section 6 | E10 |
| `UnbackedMintSuspected` | reorg deeper than the confirmation depth | pause, compare locked vs supply, raise the depth | alert test; the loss itself: E6 unsafe, `ChaosPropertyTest` |
| `DepositStuck` | relayer down, key without gas, wrong relayer address | section 2 | E1, E1b, crash test |
| `CursorStalled` | relayer not scanning, RPC down | check process and RPC | alert test only (no end-to-end test of a stalled node) |
| `ServiceErrorsHigh{reason="tx_reverted"}` | repeating reverted calls, e.g. the proposer's `NotMonotonic` after a lying output | read the revert name in the log; usually self-heals | observed in the drill ([OBSERVABILITY.md](OBSERVABILITY.md)) |
| `ServiceErrorsHigh{reason="rpc_transport"}` | RPC node down | restore the node; services retry by themselves | `ChaosPropertyTest` (12 % injected transport errors) |
| `TargetDown`, `ServiceStalled` | process died or hangs | restart it | drill |
| A withdrawal stays Proven past its window | challenged output earlier in the chain, or paused | sections 4 and 6 | E10, E9 |

Not covered by a test or a drill, said plainly: disk-full or corrupted SQLite files (restore by deleting the store and letting the service rebuild from the chain: the stores are caches of chain state, but this was not exercised), and an L2 that reorgs (the model assumes a trusted sequencer).
