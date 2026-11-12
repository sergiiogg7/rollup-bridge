# ADR-0007: Confirmation depth is a security parameter

Status: accepted, with an unfixed residual risk (see Cost).


**Decision.** The deposit relayer mints only after `DEPOSIT_CONFIRMATIONS` blocks and re-checks that the block hash is still canonical right before sending. Deposits whose block vanished become Dropped and return to Seen if they reappear.
**Cost / residual risk (not fixed).** At depth 0 a reorg mints unbacked funds (E6 unsafe asserts it; the chaos harness finds it in bulk). With reorgs shallower than the depth, 1000 random scripts never lose value. A reorg deeper than the depth can reuse a deposit id and leave an unbacked mint: the contract's replay guard is keyed by id alone and the service cannot repair that. It detects it (`bridge_deposit_reorged_after_mint_total`, alert `UnbackedMintSuspected`). A real fix keys the guard by id and L1 tx hash, or relays only finalized blocks; neither is done here.

