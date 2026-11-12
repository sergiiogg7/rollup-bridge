# Plan: rollup-bridge

Origin: portfolio project P2 of `JOBS.md`. Project type: optimistic-style ETH bridge between an L1 and an L2 (two Anvil chains), with withdrawal proofs, an output-root oracle, a challenge window, and Java services (relayer, proposer, challenger). Solidity with Foundry plus Java 25. Optional stretches: a toy interactive dispute game and a Bitcoin SPV verifier. No frontend.

Written 2026-11-12. Follows `PROGRESS.md`: one repo at a time, no commit or push without the owner's approval, no `Co-Authored-By` lines, never claim an audit.

| Date | Change |
|---|---|
| 2026-11-12 | Plan written. |
| 2026-11-12 | Core built autonomously (contracts, Java services, E2E scenarios, demo, docs). See "Status" and "Deviations" below. |

## Status (2026-11-12, core complete, stretches not started)

| Phase | State |
|---|---|
| 0 Bootstrap and spikes | Done. Spike result: web3j-crypto 6.1.0, Jackson 2.23.0 and sqlite-jdbc 3.54.1.0 all work on JDK 25.0.5 (signing a transaction, sending it to Anvil, SQLite round trip). Two native Anvils from a JUnit fixture work; historical `eth_call` works (the proposer reads root/count at the L2 head). |
| 1 Contracts | Done. 121 Foundry tests (unit, fuzz, 7 invariants, 25 named adversarial tests), 100 % lines/statements/branches/functions of `src/`, `forge fmt` clean, `.gas-snapshot` present, vectors generator, deploy scripts. Slither NOT run (no Python on the machine). |
| 2 Java core | Done. 29 tests, 97.4 % line coverage of `core`, differential tests against the Solidity vectors (every size 0..200, proofs) and against the deployed contracts on Anvil. |
| 3 Gateways, store, deposit relayer | Done, E1, E1b, both E6 variants, crash test. |
| 4 Proposer, withdrawal relayer | Done: E2, E5, E5b, E7, E8, E11. |
| 5 Challenger, guardian | Done: E3 (+E3b four kinds of lie, E3c, E3d), E4, E9, E10, negative "nobody challenges" test, expiring challenge, guardian replacement. |
| 6 Chaos and reliability | Partial. Crash-after-transaction injection (`FaultyL1Writer`) and `kill`-style restarts against a SQLite file are covered. NOT done: gateway fault decorators (latency, duplicated or out-of-order logs, stale heads), two instances running concurrently, 1000 random interleavings. |
| 7 Observability, compose devnet | NOT done: no metrics, no Prometheus/Grafana, no Docker artifacts. What exists: a plain-text status page and `status` command. |
| 8 Docs and release | Docs done (README with Mermaid, SPEC, THREAT-MODEL, SECURITY, CLAUDE.md, DEMO with real output, CI workflow untested). NOT done: RUNBOOK, ADR files, CHANGELOG, release workflow, tag, GitHub metadata (owner's decision). |
| Stretch A (bisection game) | NOT started. |
| Stretch B (Bitcoin SPV) | NOT started. |

Verification numbers (all run on 2026-11-12): `forge test` 121 passed (also with `FOUNDRY_PROFILE=ci`: 5000 fuzz runs, invariants 512 x 256); `forge coverage` 100 % of 256 lines in `src/`; `./mvnw -T 1 verify` 55 Java tests (29 core + 26 end-to-end), 0 skipped; `scripts/demo.sh` exit 0 with all assertions passing.

## Deviations from the plan

1. **Maven, not Gradle** (instruction of the owner). Wrapper committed; modules `core`, `adapters`, `services`, `testkit`, `app`, `e2e` instead of one module per service. `gateways` and `store-sqlite` merged into `adapters`.
2. **No web3j-core**: a small JSON-RPC client over `java.net.http` + Jackson, with web3j-crypto only for signing. The Keccak-256 used by `core` is hand-written (zero dependencies), verified against `cast keccak` and the Solidity vectors.
3. **Libraries are not vendored or submodules**: `scripts/install-deps.sh` runs `forge install --no-git` (the folder was not a git repo). Convert to submodules when the repo is created if preferred.
4. **`OutputOracle` additions** beyond section 3.1:
   - Global gate: `isFinalizable` also requires `openChallenges == 0`, so a later output cannot finalize while an earlier one is being judged.
   - `expireChallenge`: after `RESOLUTION_PERIOD` anyone can delete the output (and later ones) and refund every bond. A silent guardian can no longer freeze the bridge or burn bonds.
   - Cascade deletion refunds the bonds of collateral outputs (not slashed); the guardian emergency `deleteFrom` never confiscates bonds without a challenger.
   - Pull payments (`credits`, `withdrawCredits`) for all bond money.
5. **`L1Bridge`**: proof length must be exactly 20; `totalLocked`/`totalClaimable` accounting with `amount <= totalLocked` as a backstop against unbacked mints; the guardian address is read from the oracle (single source). The tree holds `2**20 - 1` leaves.
6. **Demo ports** are 18545/19545/18088 (not 8545/9545) so the demo can coexist with `go-mini-rollup`'s devnet; override with `L1_PORT`/`L2_PORT`/`STATUS_PORT`. The fake-leaf attack in the demo is a `demo-attack` command of the CLI that uses the testkit `MaliciousProposer` (labelled demo-only).
7. **Legacy gas pricing**: transactions use `2 * baseFee + 1 gwei` computed from the latest block. Using `2 * eth_gasPrice` made Anvil's price snowball across many transactions (found by the test suite).
8. **Chain ids**: L1 31337, L2 901, as planned. Lab windows: tests 100 s, demo 120 s, contracts' defaults in `DeployL1` are 7 days / 3 days.

## Known issues and sharp edges found while building

- Deposit ids are an L1 counter; after a reorg a different deposit can reuse an id the relayer already minted (only possible at too-low confirmations). See THREAT-MODEL residual risks.

## 1. Goal and scope

**Goal:** show that the owner can design, implement and attack a bridge: how value moves between layers, what proves a withdrawal, why there is a challenge window, who can steal what if one assumption breaks, and how a reorg on the source chain turns into lost funds if the relayer is careless.

**In scope**
- L1 contracts: `L1Bridge` (deposits, prove, finalize) and `OutputOracle` (proposed output roots, bonds, challenges).
- L2 contracts: `L2Bridge` (finalize deposits, initiate withdrawals) with an incremental Merkle tree of withdrawals, and `BridgedETH` (ERC-20).
- Java services: deposit relayer, proposer, withdrawal relayer (prove and finalize), challenger, and a guardian CLI.
- Independence from `java-mini-rollup`: everything is tested with two Anvil chains. Plugging into the mini rollup after its Phase 10 (EVM) is an optional extension.
- Adversarial contract tests and end-to-end attack scenarios, including L1 reorgs with `anvil_reorg`.
- Differential tests between the Solidity tree and the Java tree.
- Metrics and a compose devnet.
- Stretch A: bisection dispute game over a toy VM (a real fault-proof mechanism at small scale).
- Stretch B: Bitcoin SPV verifier in Java (headers, proof of work, Merkle inclusion) tested on regtest.

**Out of scope (written in `SECURITY.md`)**
- ERC-20 bridging, arbitrary cross-chain messages, native ETH minting on L2 (the L2 representation is an ERC-20).
- Fault proofs for the real L2 state transition: the MVP replaces them with a **trusted guardian** that resolves challenges. This is a deliberate, documented trust assumption.
- Production use, audit, economic modelling of bonds.

## 2. Verified facts and open spikes (as of 2026-11-12)

| Fact | Source | Consequence |
|---|---|---|
| Foundry 1.7.1 is installed; no JDK, Gradle or Docker yet. 7.9 GB RAM. | Checked in the session | Phase 0 installs JDK and Gradle. Tests use two native `anvil` processes, no Docker. |
| `anvil_reorg [depth, [[tx, blockIndex], ...]]`, `anvil_rollback [depth]`, `evm_snapshot` / `evm_revert [id]`, `evm_increaseTime [seconds]`, `anvil_mine [blocks, interval]`, `anvil_setBalance [address, wei]`. | getfoundry.sh/anvil/rpc-methods | Window and reorg tests do not need real waiting: advance time and mine. |
| JDK 25 is the target (Besu docs recommend it). web3j 6.0.0 exists (2026-07-08). | Besu docs, LF Decentralized Trust blog | Same toolchain as `java-mini-rollup`. |

**Spikes (Phase 0):**
- **S1:** web3j 6.0.0 on JDK 25: send transactions, read logs by block hash, call a contract at a historical block number (Anvil keeps recent history).
- **S2:** a JUnit fixture that starts two `anvil` child processes (L1 chain id 31337, L2 chain id 901), and resets them between tests with `evm_snapshot` / `evm_revert`.
- **S3:** confirm that `eth_call` at a past block works on Anvil 1.7.1 for the L2 `withdrawalRoot` (the proposer needs the root as of block X).

## 3. Design

### 3.1 Contracts

All contracts use the pinned Foundry config and CI of the reference repo `staking-protocol` (see `BEST_PRACTICES.md`), OpenZeppelin v5.4.0, custom errors, NatSpec, `Ownable2Step`, and the 100 % line coverage and Slither 0 findings standard of the other repos.

**Withdrawal** = `(uint256 id, address sender, address target, uint256 amount)`; `withdrawalHash = keccak256(abi.encode(id, sender, target, amount))`. The leaf is that hash (128-byte preimage), internal nodes hash two `bytes32` (64-byte preimage), so a leaf can never be confused with an inner node (ADR-0002).

**L2 side**
- `BridgedETH`: ERC-20, `mint` and `burn` only by `L2Bridge`.
- `L2Bridge`:
  - `finalizeDeposit(depositId, to, amount)` only by `RELAYER`, with replay guard `processedDeposits[depositId]`; mints `BridgedETH`.
  - `initiateWithdrawal(target, amount)`: burns from `msg.sender`, appends the leaf to the tree, emits `WithdrawalInitiated(id, sender, target, amount, leaf, newRoot)`.
  - `withdrawalRoot()` and `withdrawalCount()` views.
- `IncrementalMerkleTree` library: depth 20 (about one million leaves), the Ethereum deposit-contract algorithm with **commutative** keccak hashing (sorted pair) so proofs verify with OpenZeppelin `MerkleProof`. `zeros[0] = 0`, `zeros[i+1] = hash(zeros[i], zeros[i])`. Full-tree error reverts.

**L1 side**
- `OutputOracle` (owner `Ownable2Step`, immutable `CHALLENGE_WINDOW`, `RESOLUTION_PERIOD`, `PROPOSER_BOND`, `CHALLENGER_BOND`; roles `proposer` and `guardian` settable by the owner):
  - `Output { bytes32 withdrawalRoot; bytes32 l2BlockHash; uint64 l2BlockNumber; uint64 withdrawalCount; uint64 proposedAt; Status status }` with `Status` in `Proposed`, `Challenged`, `Deleted`.
  - `proposeOutput(root, l2BlockHash, l2BlockNumber, count)` payable, only proposer, bond required; `l2BlockNumber` strictly increasing and `count` non-decreasing (cheap sanity checks).
  - `challengeOutput(index)` payable with bond, only inside the window and only for `Proposed`.
  - `resolveChallenge(index, proposerWasWrong)` only guardian, inside `RESOLUTION_PERIOD`. If the proposer was wrong: `deleteFrom(index)` (it deletes this output **and every later one**, because each root commits to all earlier withdrawals) and the proposer bond goes to the challenger together with the challenger's bond back. If the challenger was wrong: the output returns to `Proposed` with a fresh window and the challenger bond goes to the proposer.
  - `deleteFrom(index)` also callable by guardian as an emergency before finality.
  - `isFinalizable(index)`: `Proposed` and `now >= proposedAt + CHALLENGE_WINDOW`. A `Challenged` output never finalizes until resolved (guardian liveness is a stated assumption; the owner can replace an unresponsive guardian).
  - Proposer bond is claimable after the output survives its window.
- `L1Bridge`:
  - `depositETH(to)` payable: reverts on zero value or a per-deposit cap; global `maxTotalLocked` cap (an early-stage bridge safety limit); emits `DepositInitiated(depositId, from, to, amount)`.
  - `proveWithdrawal(w, outputIndex, proof)`: the output must be `Proposed` and not deleted; `MerkleProof.verifyCalldata(proof, output.withdrawalRoot, leaf)`; `w.id < output.withdrawalCount`; stores `(outputIndex, provenAt)`. Re-proving is allowed only if the earlier output was deleted.
  - `finalizeWithdrawal(w)`: needs a proof, the proving output must still be `Proposed` and `isFinalizable`, not already finalized; **effects before interaction**; pays with `call`; if the transfer fails the amount is credited to `claimable[target]` (pull payment) so one bad target never blocks the bridge; `claim()` withdraws credits.
  - `pause` and `unpause` by guardian (blocks deposits, prove and finalize, never blocks `claim`).
  - Reentrancy guard on every ETH-moving function.

**Honest limitation:** the guardian decides challenges. A real system replaces `resolveChallenge` by a dispute game that is verified on-chain (Stretch A shows the mechanism on a toy VM).

### 3.2 Withdrawal and deposit lifecycles (state machines, sealed in Java and enums in Solidity)

Deposit: `Seen -> Confirmed (depth k) -> Relayed (L2 tx mined) -> Done`. Withdrawal: `Initiated -> Included (in a proposed output) -> Proven -> Finalizable -> Finalized`, with side exits `Challenged`, `Invalidated` (output deleted; back to `Included` when a new output covers it) and `PaidToClaimable`.

### 3.3 Java services

Gradle multi-module, hexagonal: `core` (tree, models, state machines) depends on nothing; ports `L1Gateway` and `L2Gateway` (read and write kept in separate small interfaces), adapters with web3j, SQLite store through plain JDBC (`org.xerial:sqlite-jdbc`; analogy: the repository layer the owner already knows), metrics with Micrometer.

- **Deposit relayer:** pull loop with a persisted cursor over L1 `DepositInitiated` events, queried by block hash. Relays only events whose block has at least `DEPOSIT_CONFIRMATIONS` confirmations **and** whose block hash is still canonical right before sending. Idempotent: the L2 contract's replay guard is the last line, the store is the first.
- **Proposer:** every `PROPOSE_EVERY_N_BLOCKS` L2 blocks (or when new withdrawals exist) reads `withdrawalRoot`, `withdrawalCount` and the block hash as of block X on L2 and sends `proposeOutput` with the bond. A `MaliciousProposerMode` exists only in `testkit`.
- **Withdrawal relayer:** for each withdrawal covered by an output, builds the proof over the first `count` leaves, sends `proveWithdrawal`, waits until `isFinalizable`, sends `finalizeWithdrawal`. Handles deleted outputs by re-proving against the next valid one. Persists the state after each step and is idempotent on restart.
- **Challenger:** watches proposed outputs inside their window. It rebuilds the withdrawal tree independently from L2 `WithdrawalInitiated` events up to `l2BlockNumber` and compares root, count and block hash. On mismatch, it sends `challengeOutput` with the bond. It never trusts the proposer's L2 node: it reads its own.
- **Guardian CLI:** `guardian resolve <index>` recomputes the same check independently and sends `resolveChallenge`; `guardian pause`. Documented as the single trust point.
- **Config:** L1 and L2 RPC URLs, contract addresses, keys (env only; test keys only), confirmation depths, windows, poll intervals. A `.env.example` is committed; real keys never.

## 4. Repository layout

```
rollup-bridge/
  contracts/        foundry.toml, src/l1, src/l2, src/lib, src/interfaces, test/, script/
  services/         settings.gradle.kts, libs.versions.toml
    core/           WithdrawalTree, models, state machines, codecs
    gateways/       ports and web3j adapters
    store-sqlite/   JDBC repositories, schema.sql
    deposit-relayer/  proposer/  withdrawal-relayer/  challenger/  guardian-cli/
    app/            mains and wiring (manual constructor injection)
    testkit/        TwoChainsFixture (two anvils), FakeGateway, MaliciousProposer, builders
    btc-spv/        (Stretch B)
  contracts/src/disputes/   (Stretch A)
  ops/              docker-compose.yml, prometheus.yml, grafana/
  docs/             SPEC.md, RUNBOOK.md, THREAT-MODEL.md, adr/
  testdata/         tree-vectors.json (generated by Solidity, consumed by Java)
```

**ADR list:** 0001 JDK 25 + Gradle; 0002 leaf/inner-node domain separation and commutative hashing; 0003 ERC-20 representation of ETH on L2; 0004 guardian-resolved challenges (trust assumption) and bonds; 0005 outputs commit to the whole withdrawal tree and `deleteFrom` semantics; 0006 pull payments for failed transfers; 0007 confirmation depth policy; 0008 SQLite store and idempotent state machines; 0009 dispute game scope (Stretch A).

## 5. Toolchain
Same as `java-mini-rollup` (JDK 25 Temurin, Gradle wrapper, JUnit 5, AssertJ, jqwik, Awaitility, ArchUnit, JaCoCo, Spotless) plus `sqlite-jdbc`, Foundry 1.7.1, OpenZeppelin v5.4.0 as a submodule, Slither in a local venv (`python -m venv v; v/Scripts/pip install slither-analyzer`) because it is not installed globally. Pin versions in `docs/TOOLCHAIN.md` at Phase 0.

## 6. Phases

Estimates are focused working days (about 6 h), orientative: core 33 days, stretches 14. Every phase starts with a mentoring checkpoint and ends with tests green, docs updated, `PROGRESS.md` row updated and the owner's go.

### Phase 0: environment and bootstrap (2 days)
- Install JDK 25 and Gradle (see `java-mini-rollup/PLAN.md` section 5). If that repo already did this phase, only verify.
- `git init`, `.gitignore`, license, `CLAUDE.md`, `README` stub. `contracts/` with `forge init`, pinned config, OpenZeppelin submodule. `services/` skeleton with the module graph and ArchUnit rule. CI skeleton with jobs `contracts` (fmt, build, test, coverage, Slither) and `services`.
- Spikes S1 to S3 plus ADR-0001.
- **Exit:** CI green on the skeleton; the two-Anvil fixture starts, mines and resets in a test.

### Phase 1: contracts (6 days)
Mentoring: how a bridge holds funds, why a root commits to a tree, domain separation, checks-effects-interactions applied to payouts, pull vs push payments, bonds and what they are for.
- `IncrementalMerkleTree`, `BridgedETH`, `L2Bridge`, `OutputOracle`, `L1Bridge` with interfaces.
- Tests, written first and red before green: unit; fuzz (any set of leaves yields a verifiable proof for every leaf; wrong sibling never verifies); invariants (`L1Bridge` solvency: balance plus credited claims plus total paid equals total deposited; a withdrawal hash is finalized at most once; no output finalizes before its window; `L2Bridge` supply of `BridgedETH` equals relayed deposits minus withdrawn amounts; withdrawal count only grows).
- Adversarial contract tests (each is a named test):

| Attack | Expected |
|---|---|
| Finalize twice (same or different proving output) | Reverts |
| Forged proof, proof for a leaf of another output, proof with a flipped sibling | Reverts |
| Prove with a tree index at or above `withdrawalCount` | Reverts |
| Finalize before the window; finalize while `Challenged` | Reverts |
| Challenge after the window or without bond | Reverts |
| Non-proposer proposes; non-guardian resolves; non-relayer finalizes a deposit | Reverts |
| Replay of a deposit on L2 | Reverts |
| Target contract reverts on receive | Credited to `claimable`, bridge not blocked |
| Target re-enters `finalizeWithdrawal` or `claim` | Blocked, balance consistent |
| Fake leaf in a root, deleted via `deleteFrom`, later output also deleted | Fake withdrawal can never finalize |
| Deleted output then re-prove against a good output | Works once |
| Pause during in-flight withdrawals; `claim` still works | As designed |
| Deposit cap and `maxTotalLocked` exceeded | Reverts |

- `GenerateTreeVectors.s.sol` writes `testdata/tree-vectors.json` (leaves, roots at every size, proofs).
- Deploy scripts `DeployL1`, `DeployL2`, `LocalDeploy` (like the other repos).
- **Exit:** 100 % line coverage, Slither 0 findings (inline silences with a reason), `forge fmt` clean, gas snapshot committed, ADR-0002 to 0006 written.

### Phase 2: Java core (4 days)
Mentoring: the same tree seen from Java, hexagonal ports, sealed types for state machines.
- `WithdrawalTree` (incremental insert, root, proof over the first `n` leaves), `Withdrawal`, hashing with web3j `Hash.sha3` (Keccak, not SHA3-256), ABI encoding of the leaf.
- Sealed state machines for deposits and withdrawals with a transition table test.
- **Differential tests:** Java root and proofs equal the Solidity vectors for every size up to 200; random leaves go through the deployed contract on Anvil and the roots match; proofs built in Java verify in the contract and vice versa.
- **Exit:** differential suite green in CI (Anvil installed with `foundry-toolchain`); 90 % line coverage on `core`.

### Phase 3: gateways, store and deposit relayer (4 days)
Mentoring: event logs, why you query by block hash, confirmations and finality on the source chain, idempotent consumers (analogy: Kafka consumer with offsets).
- Ports and web3j adapters; SQLite schema and repositories; cursor persistence.
- Deposit relayer with the confirmations rule and the canonical-hash recheck.
- **Tests:** E1 happy deposit. E6 reorg scenario in two variants: relaying at **zero confirmations** loses funds (the test asserts the L2 mint exists with no backing on L1, and the doc explains it), relaying at `k` confirmations is safe. Crash between L2 send and store commit leaves no duplicate mint (replay guard).
- **Exit:** both E1 and both E6 variants pass; the unsafe variant is kept as a documented demonstration.

### Phase 4: proposer and withdrawal relayer (4 days)
Mentoring: what an output root commits to and why it covers everything so far, the window as a security parameter, proof construction.
- Proposer loop, withdrawal relayer (prove, wait, finalize), re-proving after deletion.
- **Tests:** E2 full withdrawal using `evm_increaseTime` and `anvil_mine` to cross the window; E5 relayer crash between prove and finalize; E7 L1 reorg removes the `proposeOutput` transaction (prove fails, relayer retries after the output is posted again); E8 proposer restart; E11 payout to a reverting target through the full stack.
- **Exit:** the happy path works from `cast send` on L2 to ETH received on L1.

### Phase 5: challenger and guardian (4 days)
Mentoring: optimistic security model, who must be online, what a bond prices, why the guardian is a trust assumption and what replaces it.
- Challenger and guardian CLI. `MaliciousProposerMode` in `testkit`: fake leaf, wrong count, wrong block hash, root from another chain.
- **Tests:** E3 malicious proposer with a fake leaf: challenger challenges, guardian resolves, output and later outputs are deleted, the fake withdrawal never pays; E4 honest proposer with a malicious challenger: challenger loses its bond; E9 deleted output then honest re-proposal; E10 pause; challenger offline longer than the window shows the attack succeeding (kept as a documented negative test that states the liveness assumption); unresponsive guardian replaced by the owner.
- **Exit:** every attack row of `THREAT-MODEL.md` has a test or an explicit "accepted risk" entry.

### Phase 6: chaos and reliability (4 days)
- Fault injection decorators on both gateways (errors, latency, duplicated or out-of-order logs, RPC returning stale heads). `kill -9` tests per service at each state transition. Two instances of the same service running by mistake (idempotency).
- Property test: random interleavings of deposits, withdrawals, reorgs and restarts end with all invariants true (conservation of value between both chains).
- **Exit:** invariants hold over 1 000 random scripts in the CI profile.

### Phase 7: observability and compose devnet (2 days)
- Metrics: `bridge_deposits_pending`, `bridge_deposit_latency_seconds`, `bridge_withdrawals_by_state{state}`, `bridge_outputs_proposed_total`, `bridge_outputs_challenged_total`, `bridge_l1_confirmations_waiting`, `bridge_service_errors_total{service,reason}`, `bridge_cursor_lag_blocks{chain}`. Alerts: an output near the end of its window unchecked, cursor stalled.
- Dockerfile, `ops/docker-compose.yml` (two Anvil, services, Prometheus, Grafana) with memory limits and a lab `CHALLENGE_WINDOW` of 120 s; native script alternative without Docker.
- **Exit:** the demo script runs deposit, withdraw and a caught attack end to end and the dashboard shows each state.

### Phase 8: documentation and release v0.1.0 (3 days)
- `README` with a Mermaid sequence diagram of deposit and withdrawal, quickstart, and a demo recording. `SPEC.md`, `THREAT-MODEL.md` (actors, assets, trust assumptions, attacks and mitigations, residual risks), `RUNBOOK.md` (stuck withdrawal, stuck deposit, challenged output, guardian rotation, pause), `SECURITY.md` (no audit; limitations), ADRs, `CHANGELOG.md`.
- `release.yml`, tag `v0.1.0`, GitHub release, About and topics (below). Only with the owner's approval.
- **Exit:** a stranger can run the demo and understand the trust model from `THREAT-MODEL.md`.

### Stretch A: interactive dispute game with a toy VM (8 days, optional, after v0.1.0)
Mentoring: what a fault proof is, bisection, the one-step proof, clocks and bonds.
- **Toy VM:** 4 registers of `uint64`, a program of at most 64 instructions (`MOV`, `ADD`, `SUB`, `MUL`, `JNZ`, `HALT`), state hash `keccak(pc, r0..r3)`. The program hash is committed on-chain.
- **`BisectionGame`:** the proposer claims the state hash after `N` steps from the initial state; the challenger disputes; both alternately post the midpoint hash, halving the interval until a single step is contested; the contract executes that one instruction on-chain and decides; per-move clock; the loser's bond is slashed. Multiple games may run in parallel.
- **Java participants:** honest bot and a lying bot; a Java reference VM. **Differential fuzz:** Solidity `step` vs Java `step` for random states and instructions.
- **Link to the bridge (design only, ADR-0009):** the program a real system would bisect is the L2 state transition (MIPS-like VM in OP-style systems); here it replaces nothing and the guardian remains.
- **Exit:** honest wins against any lying strategy in randomized games; every contract path covered; the game costs `O(log N)` moves (asserted in a test).

### Stretch B: Bitcoin SPV verifier (6 days, optional)
Mentoring: Bitcoin block header (80 bytes), double SHA-256, `nBits` and the target, difficulty retarget every 2016 blocks, Merkle branches, why confirmations matter on a PoW chain.
- Module `btc-spv`: header parsing, hash, proof-of-work check, chain linkage, retarget rule, Merkle inclusion, a confirmation-depth policy.
- Tests: known mainnet header vectors (genesis and a retarget boundary), cross-check against `bitcoinj` as a differential oracle, then `bitcoind -regtest` (binary or Docker) generating blocks and a transaction and verifying the proof from `getblockheader` and `gettxoutproof`.
- Deliverable: CLI `btc-spv verify` and `docs/BTC-PEGIN.md`, a design note on how a verified Bitcoin deposit could mint on the L2 and what each trust assumption would be. No on-chain verifier in scope.
- **Exit:** the regtest demo verifies an inclusion proof and rejects a tampered one.

## 7. Test strategy summary

| Level | Tools |
|---|---|
| Contract unit, fuzz, invariant, adversarial | Foundry, 100 % line coverage, Slither |
| Differential Solidity vs Java | `testdata` vectors plus live Anvil |
| Java unit and property | JUnit 5, AssertJ, jqwik |
| Integration | two native Anvil processes, SQLite in a temp dir |
| End to end attacks | `TwoChainsFixture`, `MaliciousProposerMode`, `anvil_reorg`, `evm_increaseTime` |
| Fault injection and chaos | gateway decorators, kill -9 per transition |
| Architecture | ArchUnit |

## 8. CI and release
`contracts` job (fmt check, build, test, coverage gate, Slither fail on low), `services` job (Spotless, tests, JaCoCo, differential and E2E with Anvil from `foundry-toolchain`), nightly long CI profile. After every push, `gh run list` to confirm it is green. `release.yml` on tags.

## 9. Risks and fallbacks

| Risk | Fallback |
|---|---|
| Historical `eth_call` unsupported or flaky on Anvil | Proposer records the root from `WithdrawalInitiated` events (`newRoot`) instead of calling the view. |
| Two Anvils plus services too heavy for 7.9 GB | Run Anvils natively (light), no Docker in tests, close other apps for the compose demo. |
| Guardian model judged too weak | Stretch A is the answer to "what would replace it"; keep it described in ADR-0004. |
| Differential tests find real divergences | They are the point; fix the Java or the Solidity and keep the vector as a regression. |
| Scope | Stretches are cut first; the core is a finished deliverable on its own. |

## 10. Definition of done
- Phases 0 to 8 closed; `v0.1.0` released with the owner's approval.
- Contracts: 100 % coverage, Slither 0, invariants and every row of the adversarial table green.
- Services: crash, reorg and fault scenarios pass; zero-confirmation loss and `k`-confirmation safety both demonstrated.
- `THREAT-MODEL.md` complete; `SECURITY.md` states no audit and lists limitations.
- `PROGRESS.md` row and `JOBS.md` updated.

## 11. What the owner can say in an interview
- "I built a bridge with deposits, proven withdrawals, a challenge window and bonds, and a threat model that says what breaks if the guardian or the challenger is offline."
- "My Java and Solidity trees agree bit for bit; I check it with differential tests, not by eye."
- "I can show a reorg that mints unbacked funds at zero confirmations and the fix with `k` confirmations."
- "The MVP trusts a guardian; here is the toy bisection game that shows how a real system removes that trust, and what it would take to apply it to a real VM."

## 12. GitHub metadata
- About: "Educational optimistic ETH bridge: Solidity contracts (Foundry), Java relayer, proposer and challenger, withdrawal proofs, challenge window and attack scenarios including L1 reorgs."
- Topics: `solidity`, `foundry`, `java`, `bridge`, `layer2`, `optimistic-rollup`, `merkle-proof`, `smart-contract-security`, `ethereum`, `web3j`.
