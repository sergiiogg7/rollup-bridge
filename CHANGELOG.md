# Changelog

All notable changes to this project. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Nothing has been released yet (no tag, no GitHub release): everything is under Unreleased.

## [Unreleased]

### Added

- Contracts (Foundry): `L1Bridge`, `OutputOracle`, `L2Bridge`, `BridgedETH`, an incremental Merkle tree and the withdrawal hashing library; 121 tests (unit, fuzz, invariants, named adversarial scenarios), 100 % line coverage of `src/`, Slither in CI.
- Java services (Maven, JDK 25): deposit relayer, proposer, withdrawal relayer, challenger and guardian over small ports; JSON-RPC and SQLite adapters; sealed state machines; a CLI with a status page; end-to-end scenarios on two native Anvils; differential tests of the Java Merkle tree against Solidity.
- `scripts/demo.sh`: deposit, withdrawal, an attack that the challenger catches.
- Metrics: a dependency-free Prometheus registry in `core`, `/metrics` on `STATUS_PORT` for every role, metered ports, tick bookkeeping and a bridge-wide collector (deposits, withdrawals, outputs, challenge windows, cursor lag, errors by reason).
- `monitoring/`: Prometheus configuration, ten alert rules with `promtool` unit tests, a provisioned Grafana dashboard; `scripts/observability-{up,down,check,drill}.sh` run it natively and prove the alerts fire in a controlled failure ([docs/OBSERVABILITY.md](docs/OBSERVABILITY.md)).
- Fault injection (`Faults`) for any port: errors, lost responses, latency, duplicated and shuffled logs, stale heads, crashes after a mined transaction.
- An in-memory model of both chains (`SimWorld`) and a randomized chaos run: 1000 scripts of deposits, withdrawals, L1 reorgs, restarts, flaky RPCs, lying proposers and pauses, with safety invariants after every step and exact value conservation at the end (`CHAOS_SCRIPTS`, `CHAOS_SEED`).
- Documentation: SPEC, THREAT-MODEL, RUNBOOK, OBSERVABILITY, DEMO, ADRs in `docs/adr/`, SECURITY.
- MIT license, CI and release workflows (the release workflow only runs on a `v*` tag).
- An interactive bisection dispute game on a toy VM (`contracts/src/disputes`), not wired into the bridge.

### Fixed

- Challenger: an output whose challenge transaction was removed by an L1 reorg was never challenged again.
- Withdrawal relayer: a withdrawal stored as Proven whose prove transaction was reorged out retried a reverting finalize for ever; one stored as Finalized whose finalize was reorged out was never paid.
- Output verifier: duplicated, reordered or missing logs from a flaky L2 node, or a lagging head, made it accuse an honest proposer; the check is now inconclusive (retried) instead.
- Domain counters are visible at zero before their first event.

### Known limitations

- The guardian is trusted; a dishonest guardian can let a lying output finalize.
- A reorg deeper than the confirmation depth can leave an unbacked mint (deposit id reuse); it is detected and alerted (`UnbackedMintSuspected`), not repaired. See [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md).
- Not audited, not for real value.
