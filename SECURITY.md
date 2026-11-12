# Security

## Status

**This project has not been audited by any external party.** It is an educational portfolio project and must not be used with real funds. There is no bug bounty.

## What was done

- Foundry tests: unit, fuzz, stateful invariants (L1 solvency, no double finalize, nothing finalizes early or during a challenge, L2 supply conservation, bond conservation) and one named test per adversarial scenario. 100 % line and branch coverage of `contracts/src` (`forge coverage`).
- Differential tests between the Solidity and Java Merkle trees (shared vectors plus the deployed contracts on a live Anvil).
- End-to-end attack scenarios with two Anvil chains (malicious proposer, malicious challenger, reorgs, crashes, pause).
- Pinned compiler (`solc 0.8.28`, EVM `cancun`), custom errors, checks-effects-interactions, `ReentrancyGuard` on every ETH-moving function, `Ownable2Step`, pull payments for failed transfers and bonds, caps on deposits.

## What was NOT done (be honest about it)

- **No Slither.** The machine this was built on has no working Python (only Windows Store stubs), so Slither could not be run locally. `crytic/slither-action` is configured in `.github/workflows/ci.yml` (`fail-on: low`) but has not run yet; expect findings to triage on the first CI run. Local checks used instead: `forge build` (no compiler or lint warnings), `forge fmt --check`, fuzzing, invariants and coverage.
- No external audit, no formal verification, no mutation testing.
- The CI workflow itself has not been run (the project is not on GitHub yet).
- No monitoring or alerting; no operational runbook.

## Known design limitations (details in docs/THREAT-MODEL.md)

- The **guardian** is a trusted party that decides challenges. Replacing it with an on-chain fault-proof game is out of scope.
- Safety needs **at least one honest online challenger** per output window. A test (`challengerOfflineLongerThanTheWindowLetsTheAttackThrough`) shows the theft succeeding when nobody watches.
- The deposit relayer is trusted; at `DEPOSIT_CONFIRMATIONS=0` an L1 reorg loses funds (test `e6_zeroConfirmationsMintsUnbackedFundsAfterReorg`).
- Deposit ids are an L1 counter and can be reused after a reorg.
- The owner has unrestricted configuration power (no timelock, no multisig).
- The local demo uses the **public Anvil test keys**. They are printed in this repo on purpose and control nothing of value on any real network.

## Reporting

This is a learning project; open an issue or contact the author. Do not report findings in `Anvil` test keys.
