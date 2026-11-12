# ADR-0001: JDK 25 and Maven, not Gradle

Status: accepted.


**Context.** The services are Java; the owner asked for Maven. JDK 25 is the current LTS; sealed types, records and pattern matching in `switch` carry the state machines.
**Decision.** A Maven multi-module build with the wrapper committed: `core` (no I/O), `adapters`, `services`, `testkit`, `app` (shaded jar), `e2e`. One JVM and one fork for tests (`-T 1`): two Anvils plus a JVM must fit an 8 GB laptop. No web3j-core: a small JSON-RPC client over `java.net.http` and Jackson, web3j-crypto only for signing; Keccak-256 is hand-written in `core` and checked against `cast keccak` and the Solidity vectors.
**Cost.** Fewer modules than the one-per-service layout of the first plan (`gateways` and `store-sqlite` merged into `adapters`); more code of our own to maintain.

