# ADR-0002: Leaf and inner-node domain separation, commutative pair hashing

Status: accepted.


**Context.** If leaves and inner nodes hash the same number of bytes, an inner node can be presented as a leaf.
**Decision.** A leaf is `keccak256(abi.encode(id, sender, target, amount))` (128-byte preimage); an inner node hashes two words (64 bytes), sorted. `L1Bridge` also requires proofs of exactly 20 siblings. Proofs then verify with OpenZeppelin `MerkleProof` and carry no left/right flags.
**Cost.** The path does not prove a leaf's position, but the `id` is inside the leaf and `id < output.withdrawalCount` is checked. The Java tree must match bit for bit: differential tests against Solidity-generated vectors (every size 0..200) and the deployed contracts.

