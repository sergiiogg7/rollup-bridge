# ADR-0004: Challenges are resolved by a trusted guardian; bonds on both sides

Status: accepted, with a known weakness (the guardian is trusted).


**Context.** A real optimistic system settles disputes with an on-chain fault proof, which needs a verifiable VM for the L2 state transition: far beyond this project.
**Decision.** `OutputOracle.resolveChallenge` is `onlyGuardian`. Proposer and challenger post bonds; the loser's bond goes to the winner. Fail-safe: after `RESOLUTION_PERIOD` anyone can `expireChallenge` (the output and later ones are deleted, every bond refunded), so a silent guardian cannot freeze the bridge or burn bonds. The owner can replace the guardian.
**Cost.** A dishonest guardian can keep a lying output alive and the theft succeeds (stated in THREAT-MODEL and SECURITY). The toy bisection game shows the mechanism that would remove this trust, see ADR-0009.

