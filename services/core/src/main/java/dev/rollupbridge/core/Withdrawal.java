package dev.rollupbridge.core;

import java.math.BigInteger;
import java.util.List;

/**
 * A withdrawal from L2 to L1. Mirrors {@code struct Withdrawal} in the Solidity code; {@code id} is the leaf
 * index in the L2 withdrawal tree.
 */
public record Withdrawal(long id, String sender, String target, BigInteger amount) {

    public Withdrawal {
        sender = Hex.address(sender);
        target = Hex.address(target);
    }

    /**
     * {@code keccak256(abi.encode(id, sender, target, amount))}: a 128-byte preimage, whereas inner tree nodes
     * hash 64 bytes, so a leaf can never be confused with an inner node.
     */
    public Bytes32 leaf() {
        return Bytes32.of(Keccak256.hash(Abi.encode(asTuple().members())));
    }

    /** The struct as an ABI tuple (used as the first argument of prove/finalize). */
    public Abi.Tuple asTuple() {
        return new Abi.Tuple(List.of(Abi.uint(id), Abi.address(sender), Abi.address(target), Abi.uint(amount)));
    }
}
