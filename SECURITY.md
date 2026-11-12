# Security

## Status

**This project has not been audited by any external party.** It is an educational portfolio project and must not be used with real funds. There is no bug bounty.

## What was done

- Foundry tests: unit, fuzz, stateful invariants (L1 solvency, no double finalize, nothing finalizes early or during a challenge, L2 supply conservation, bond conservation) and one named test per adversarial scenario. 100 % line and branch coverage of `contracts/src` (`forge coverage`).
- Differential tests between the Solidity and Java Merkle trees (shared vectors plus the deployed contracts on a live Anvil).
- End-to-end attack scenarios with two Anvil chains (malicious proposer, malicious challenger, reorgs, crashes, pause).
- Pinned compiler (`solc 0.8.28`, EVM `cancun`), custom errors, checks-effects-interactions, `ReentrancyGuard` on every ETH-moving function, `Ownable2Step`, pull payments for failed transfers and bonds, caps on deposits.

## What was NOT done (be honest about it)

- **Slither runs only in CI, not locally.** The machine this was built on has no working Python (only Windows Store stubs). `crytic/slither-action` runs in `.github/workflows/ci.yml` with `fail-on: low` and passes. Its first run reported three low-severity patterns that are by design and are silenced inline with the reason (no detector is excluded globally): `timestamp` in `OutputOracle` (windows of hours or days, a skew of seconds is irrelevant), `reentrancy-events` in `L2Bridge` (events after `mint`/`burn` on its own token) and `missing-zero-check` in the `BridgedETH` constructor (only `L2Bridge` deploys it, with `address(this)`). A clean Slither run is not an audit.
- No external audit, no formal verification, no mutation testing.
- Monitoring is local only: Prometheus and Grafana were run natively against the demo and an alert drill passed (docs/OBSERVABILITY.md), but nothing pages anyone (no Alertmanager) and nothing was run for more than minutes.
- The randomized chaos test runs the real services against an in-memory MODEL of the contracts, not the contracts: it is evidence about the services, not about the Solidity.

## Known design limitations (details in docs/THREAT-MODEL.md)

- The **guardian** is a trusted party that decides challenges. Replacing it with an on-chain fault-proof game is out of scope.
- Safety needs **at least one honest online challenger** per output window. A test (`challengerOfflineLongerThanTheWindowLetsTheAttackThrough`) shows the theft succeeding when nobody watches.
- The deposit relayer is trusted; at `DEPOSIT_CONFIRMATIONS=0` an L1 reorg loses funds (test `e6_zeroConfirmationsMintsUnbackedFundsAfterReorg`).
- Deposit ids are an L1 counter and can be reused after a reorg deeper than the confirmation depth: the L2 mint for the orphaned deposit is then unbacked. The services detect and alert (`UnbackedMintSuspected`) but cannot repair it; the replay guard of `L2Bridge` is keyed by id alone.
- The owner has unrestricted configuration power (no timelock, no multisig).
- The local demo uses the **public Anvil test keys**. They are printed in this repo on purpose and control nothing of value on any real network.

## Reporting

This is a learning project; open an issue or contact the author. Do not report findings in `Anvil` test keys.
