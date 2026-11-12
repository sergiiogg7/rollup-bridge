# Threat model

Educational project, not audited. This document states what the design assumes, what it defends against, and what it does not.

## 1. Assets

| Asset | Where | Worth protecting because |
|---|---|---|
| ETH locked in `L1Bridge` | L1 | it backs every `bETH` on L2 |
| `bETH` balances | L2 | users own them |
| Bonds | `OutputOracle` | they make lying and griefing costly |
| The correctness of the output roots | `OutputOracle` | withdrawals are paid against them |

## 2. Actors and trust

| Actor | Trusted for | Can do if malicious | Mitigation |
|---|---|---|---|
| **Proposer** (role key) | liveness of exits | post a lying output (fake withdrawal = theft) | challenge window + bond; challenger deletes it |
| **Challenger** (anyone) | nothing; at least one honest one must exist | grief by challenging honest outputs; stay offline | bond lost if guardian rules against it; many challengers allowed |
| **Guardian** (role key) | correct verdicts, pausing | rule wrongly (steal bonds, keep a bad output alive, delete honest outputs), pause forever | **single point of trust in the MVP**; cannot move user funds directly; owner can replace it; silent guardian handled by `expireChallenge` |
| **Deposit relayer** (role key) | mirroring L1 deposits faithfully | mint unbacked `bETH` on L2 | L1 never pays more than `totalLocked`; `DEPOSIT_CONFIRMATIONS` + canonical check against honest mistakes |
| **Withdrawal relayer** (anyone) | nothing | withhold service | anyone can prove/finalize; the money always goes to `w.target` |
| **Owner** (Ownable2Step) | configuration | rotate proposer/guardian/relayer, change caps | multisig + timelock in a real deployment (not implemented) |
| **L1 / L2 nodes** | data | lie about logs or state | the services use one RPC per chain; the challenger and guardian should use independent L2 RPCs |

## 3. Trust assumptions (summary)

1. At least one honest, online challenger watches every output during its window (liveness assumption of all optimistic systems).
2. The guardian rules honestly and promptly (MVP replacement for an on-chain fault proof).
3. The L1 does not reorg deeper than `DEPOSIT_CONFIRMATIONS` (and never beyond practical finality in a real system).
4. The L2 sequencer (here: a local Anvil) is not adversarial about the data the challenger reads; a real system derives L2 from L1 data.

## 4. Attacks, mitigations and where they are tested

| # | Attack | Mitigation | Test |
|---|---|---|---|
| A1 | Finalize a withdrawal twice, directly or through a second output | `finalized[leaf]`, effects before interaction | `Adversarial.t.sol::test_attack_finalizeTwice*`, invariant `noDoubleFinalize` |
| A2 | Forged leaf, proof from another output, flipped sibling, inner node as leaf | Merkle check, 128-byte leaf vs 64-byte node, exact proof length 20 | `test_attack_forged*`, `test_attack_proofFromAnotherOutput*`, `test_attack_flippedSibling*`, `test_attack_innerNodeAsLeaf*` |
| A3 | Prove an id that the output does not cover | `w.id < output.withdrawalCount` | `test_attack_proveIdAtOrAboveWithdrawalCount` |
| A4 | Finalize before the window, or while any challenge is open (also through a *later* output) | `isFinalizable`: window and `openChallenges == 0` | `test_attack_finalizeBeforeWindow`, `..WhileChallenged`, `..laterOutputCannotFinalize...` |
| A5 | Challenge too late or without bond | window check, exact bond | `test_attack_challengeAfterWindow`, `..WithoutBond` |
| A6 | Unauthorised propose / resolve / mint | role modifiers | `test_attack_nonProposer*`, `..nonGuardian*`, `..nonRelayer*` |
| A7 | Replay a deposit on L2 | `processedDeposits[id]` | `test_attack_depositReplayOnL2`, E1b |
| A8 | Payout target reverts to block the queue | pull-payment fallback | `test_attack_revertingTarget*`, E11 |
| A9 | Target re-enters `finalizeWithdrawal` / `claim` | `nonReentrant`, effects first | `test_attack_targetReenters*` |
| A10 | Return-bomb target burns the caller's gas | return data is never copied | `test_returnBombTargetCannotBurnTheCallersGas` |
| A11 | **Fake leaf in a posted root** (theft) | challenger re-derives the tree; guardian rules; output and all later ones deleted | `test_attack_fakeLeafOutputDeleted...`, E3, E3b (4 kinds of lie), E3d |
| A12 | Re-prove after a deletion | allowed once, against a live output | `test_attack_reproveAfterDeletion...`, E9 |
| A13 | Pause during in-flight withdrawals | `claim` is never paused | `test_attack_pauseDuringInFlight...`, E10 |
| A14 | Deposit caps exceeded | caps | `test_attack_depositCapAndTotalLockedExceeded` |
| A15 | Unbacked mint on L2 (bad relayer) drains L1 | `amount <= totalLocked` | `test_attack_unbackedL2MintCannotPayMoreThanLocked` |
| A16 | **L1 reorg after the relayer minted at depth 0** | confirmations + canonical hash re-check | E6 unsafe (asserts the loss) vs E6 safe |
| A17 | Reorg removes `proposeOutput` / `prove` / `challenge` / `finalize` transactions | services derive state from the chain, not from memory; the challenger and the withdrawal relayer redo a step whose transaction vanished | E7, `ReorgResilienceTest`, `ChaosPropertyTest` (1000 random scripts) |
| A18 | Relayer or proposer crashes between a transaction and its bookkeeping | reconcile with the chain first; persisted intent markers | E5, E5b, E8, crash test in `DepositScenariosTest` |
| A19 | Malicious challenger griefs honest outputs | bond lost when the guardian rules against it | E4 |
| A20 | Silent guardian | `expireChallenge` fails safe (delete + refund), owner can replace the guardian | `unresponsiveGuardianChallengeExpires`, `ownerReplacesTheGuardian` |
| A21 | Nobody challenges (attacker wins) | **not mitigated**: it is the liveness assumption | `challengerOfflineLongerThanTheWindowLetsTheAttackThrough` (a negative test on purpose) |

## 5. Residual risks (accepted, documented)

- **Guardian trust (A21's cousin).** The guardian decides disputes. A dishonest guardian can keep a lying output alive (rule for the proposer) and then the theft succeeds. The replacement is an on-chain dispute game; the idea is described in PLAN.md (Stretch A, not implemented here).
- **Challenger liveness (A21).** Nothing forces anybody to watch. Run several independent challengers.
- **Deposit id reuse after a reorg (re-verified 2026-11-12, NOT fixed).** Ids are an L1 counter, so a reorg deeper than `DEPOSIT_CONFIRMATIONS` can give an id the relayer already minted to a *different* deposit (or remove the minted one). Then `L2Bridge.processedDeposits[id]` blocks the legitimate mint and the L2 balance minted for the orphaned deposit is unbacked. The cause is in the contract (the replay guard is keyed by id alone), which this task could not change; the service cannot repair it either, because it cannot mint twice. What the service does: it keeps the record as Done, logs a warning and increments `bridge_deposit_reorged_after_mint_total`, which raises the critical alert `UnbackedMintSuspected`. The randomized run reproduces the loss with zero confirmations and deep reorgs (`ChaosPropertyTest.theHarnessDetectsTheLossThatZeroConfirmationsAllows`) and never with reorgs shallower than the depth. A real fix keys the guard by `(id, L1 tx hash)` or relays only finalized L1 blocks.
- **Censorship by challenge.** A challenge freezes all finalizations (global gate) until resolution, so a challenger willing to lose a bond can delay exits by at most `RESOLUTION_PERIOD`.
- **Proposer gas griefing** on the cascade loop (trusted role).
- **Owner powers** are unrestricted: no timelock, no multisig.
- **No audit and no formal verification.** Slither runs in CI (see SECURITY.md); a clean run is not an audit.
- **L2 data availability is out of scope**: the challenger reads L2 from an RPC node. A real optimistic rollup derives L2 from data posted on L1.
- **Economic parameters** (bond sizes, window) are not modelled.
