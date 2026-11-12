# ADR-0005: Outputs commit to the whole withdrawal tree; deletion cascades

Status: accepted.


**Decision.** Each output holds the root and count of the entire append-only tree up to an L2 block, so a later output implies every earlier withdrawal. Therefore `deleteFrom(i)` deletes output `i` and every later one, and `isFinalizable` also requires `openChallenges == 0`.
**Cost.** One bad output delays everything after it by a re-proposal, and a challenge freezes finalizations until it is ruled on (at most `RESOLUTION_PERIOD`): accepted as "censorship by challenge" in the residual risks. Bonds of collateral outputs are refunded, not slashed.

