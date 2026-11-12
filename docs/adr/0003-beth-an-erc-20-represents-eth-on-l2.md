# ADR-0003: bETH, an ERC-20, represents ETH on L2

Status: accepted.


**Context.** Minting native ETH on a local Anvil L2 needs a chain-level hook; the subject is the bridge protocol, not an execution client.
**Decision.** `L2Bridge` mints and burns `BridgedETH`; a burn appends the withdrawal leaf.
**Cost.** Out of scope: other tokens, arbitrary messages, native-ETH semantics. In exchange value conservation is two numbers: `L1Bridge.totalLocked` and the bETH supply (the chaos test asserts equality once everything has settled).

