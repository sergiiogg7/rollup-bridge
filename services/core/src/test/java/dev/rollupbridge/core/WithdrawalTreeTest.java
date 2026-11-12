package dev.rollupbridge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Differential tests: the Java tree must equal the Solidity tree bit for bit. */
class WithdrawalTreeTest {
    static Vectors vectors;

    @BeforeAll
    static void load() throws IOException {
        vectors = Vectors.load();
    }

    @Test
    void zeroHashesMatchSolidity() {
        for (int h = 0; h <= WithdrawalTree.DEPTH; h++) {
            assertThat(WithdrawalTree.zero(h)).as("zero hash %d", h).isEqualTo(vectors.zeroHashes.get(h));
        }
    }

    @Test
    void leafHashesMatchSolidityForAllTwoHundredWithdrawals() {
        assertThat(vectors.withdrawals).hasSize(200);
        for (int i = 0; i < 200; i++) {
            assertThat(vectors.withdrawals.get(i).leaf()).as("leaf %d", i).isEqualTo(vectors.leaves.get(i));
        }
    }

    @Test
    void frontierRootEqualsSolidityRootAtEverySizeUpTo200() {
        WithdrawalTree tree = new WithdrawalTree();
        assertThat(tree.root()).as("empty tree").isEqualTo(vectors.roots.get(0));
        for (int n = 1; n <= 200; n++) {
            tree.append(vectors.leaves.get(n - 1));
            assertThat(tree.root()).as("frontier root, size %d", n).isEqualTo(vectors.roots.get(n));
        }
    }

    @Test
    void levelByLevelRootEqualsSolidityRootAtEverySize() {
        WithdrawalTree tree = new WithdrawalTree();
        vectors.leaves.forEach(tree::append);
        for (int n = 0; n <= 200; n++) {
            assertThat(tree.rootAt(n)).as("level root, size %d", n).isEqualTo(vectors.roots.get(n));
        }
    }

    @Test
    void proofsEqualSolidityProofsAndVerify() {
        WithdrawalTree tree = new WithdrawalTree();
        vectors.leaves.forEach(tree::append);
        assertThat(vectors.proofs).isNotEmpty();
        for (Vectors.ProofVector pv : vectors.proofs) {
            List<Bytes32> mine = tree.proof(pv.index(), pv.size());
            assertThat(mine).as("proof size=%d index=%d", pv.size(), pv.index()).isEqualTo(pv.proof());
            assertThat(WithdrawalTree.verify(pv.proof(), vectors.roots.get(pv.size()), vectors.leaves.get(pv.index())))
                    .isTrue();
        }
    }

    @Test
    void flippedSiblingDoesNotVerify() {
        WithdrawalTree tree = new WithdrawalTree();
        vectors.leaves.forEach(tree::append);
        List<Bytes32> proof = new ArrayList<>(tree.proof(5, 10));
        byte[] sib = proof.get(3).toBytes();
        sib[0] ^= 1;
        proof.set(3, Bytes32.of(sib));
        assertThat(WithdrawalTree.verify(proof, tree.rootAt(10), tree.leaves().get(5))).isFalse();
    }

    @Test
    void proofForAnIndexOutsideTheOutputIsRejected() {
        WithdrawalTree tree = new WithdrawalTree();
        vectors.leaves.stream().limit(5).forEach(tree::append);
        assertThatThrownBy(() -> tree.proof(5, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.proof(0, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rootOfEmptyListIsTheDepthZeroHash() {
        assertThat(WithdrawalTree.rootOf(List.of())).isEqualTo(WithdrawalTree.zero(20));
    }
}
