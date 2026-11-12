# ADR-0008: SQLite store, idempotent ticks, sealed state machines

Status: accepted.


**Decision.** Each service is a polling `tick()` that reconciles with the chain first and then acts, persisting its intent (`Relaying`) before an external side effect. State is a sealed interface of records per machine with one transition table pinned by a test. Persistence is plain JDBC over SQLite, one file per service.
**Cost.** The stores are caches: the chain is the source of truth. The randomized run found two places where a store believed a transaction that a reorg had removed (the challenger's checked mark; the relayer's Proven and Finalized states); both now re-read the chain. The re-check of finalized withdrawals is bounded to the newest 32 per tick, so a reorg deeper than that would go unnoticed.

