# ADR-0006: Pull payments when a payout fails

Status: accepted.


**Decision.** `finalizeWithdrawal` updates state first, then pays with a low-level call that ignores return data. A failed call becomes `claimable[target]`, paid by `claim()` later (also while paused). Bond money uses the same pattern (`credits`, `withdrawCredits`).
**Cost.** A reverting or gas-griefing target never blocks the bridge or the relayer (scenario E11); the affected user needs one more transaction.

