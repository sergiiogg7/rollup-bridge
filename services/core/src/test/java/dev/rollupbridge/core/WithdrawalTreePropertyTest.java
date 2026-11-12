package dev.rollupbridge.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/** Properties that must hold for any set of leaves, independent of the Solidity vectors. */
class WithdrawalTreePropertyTest {

    @Property(tries = 200)
    void frontierAndLevelAlgorithmsAlwaysAgreeAndEveryLeafHasAValidProof(
            @ForAll @Size(min = 1, max = 90) List<@Size(32) byte[]> raw,
            @ForAll @IntRange(min = 0, max = 1000) int pick) {
        WithdrawalTree tree = new WithdrawalTree();
        raw.forEach(b -> tree.append(Bytes32.of(Keccak256.hash(b)))); // hashed: an all-zero leaf would equal the zero hash
        assertThat(tree.root()).isEqualTo(tree.rootAt(tree.size()));
        int i = pick % tree.size();
        assertThat(WithdrawalTree.verify(tree.proof(i, tree.size()), tree.root(), tree.leaves().get(i))).isTrue();
    }

    @Property(tries = 100)
    void aProofOverAPrefixVerifiesAgainstThePrefixRootOnly(
            @ForAll @Size(min = 2, max = 60) List<@Size(32) byte[]> raw,
            @ForAll @IntRange(min = 1, max = 59) int prefix) {
        WithdrawalTree tree = new WithdrawalTree();
        raw.forEach(b -> tree.append(Bytes32.of(Keccak256.hash(b)))); // hashed: an all-zero leaf would equal the zero hash
        int n = Math.max(1, Math.min(prefix, tree.size() - 1));
        List<Bytes32> proof = tree.proof(0, n);
        assertThat(WithdrawalTree.verify(proof, tree.rootAt(n), tree.leaves().get(0))).isTrue();
        // the same proof does not verify against the root of the longer tree (the roots differ)
        assertThat(WithdrawalTree.verify(proof, tree.root(), tree.leaves().get(0))).isFalse();
    }
}
