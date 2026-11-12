# ADR-0009: Scope of the dispute game

Status: accepted; the game is built but not wired into the bridge.


**Decision.** A toy VM and an interactive bisection game live in `contracts/src/disputes` as a separate mechanism: two parties halve a disputed interval until one instruction is contested, which the contract executes. It does not replace `resolveChallenge`: a real system would bisect the L2 state transition itself (a MIPS-like VM), which this project does not have.
**Cost.** The guardian remains the trust point of the bridge (ADR-0004). The game answers what would remove that trust and what it would cost (O(log N) moves); its own documentation is `docs/DISPUTE-GAME.md` (written by whoever built the game).
