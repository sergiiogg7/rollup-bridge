package dev.rollupbridge.testkit;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Withdrawal;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.port.L1Writer;
import java.util.List;
import java.util.Set;

/**
 * Fault injection decorator: delegates to a real writer and then "kills the process" (throws
 * {@link SimulatedCrash}) right after the named operation succeeded on chain. That is the nastiest moment
 * for an idempotent service: the transaction is mined but the service never got to write its state.
 */
public final class FaultyL1Writer implements L1Writer {

    /** Stands for {@code kill -9}: not a GatewayException, so services do not swallow it. */
    public static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SimulatedCrash(String message) {
            super(message);
        }
    }

    private final L1Writer delegate;
    private final Set<String> crashAfter;

    /** @param crashAfter operation names to crash after: "prove", "finalize", "propose", "challenge", "resolve" */
    public FaultyL1Writer(L1Writer delegate, String... crashAfter) {
        this.delegate = delegate;
        this.crashAfter = Set.of(crashAfter);
    }

    private TxResult maybeCrash(String op, TxResult result) {
        if (crashAfter.contains(op)) {
            throw new SimulatedCrash("kill -9 right after " + op + " was mined (" + result.txHash() + ")");
        }
        return result;
    }

    @Override
    public TxResult proposeOutput(Bytes32 root, Bytes32 l2BlockHash, long l2BlockNumber, long withdrawalCount) {
        return maybeCrash("propose", delegate.proposeOutput(root, l2BlockHash, l2BlockNumber, withdrawalCount));
    }

    @Override
    public TxResult challengeOutput(long index) {
        return maybeCrash("challenge", delegate.challengeOutput(index));
    }

    @Override
    public TxResult resolveChallenge(long index, boolean proposerWasWrong) {
        return maybeCrash("resolve", delegate.resolveChallenge(index, proposerWasWrong));
    }

    @Override
    public TxResult expireChallenge(long index) {
        return delegate.expireChallenge(index);
    }

    @Override
    public TxResult deleteFrom(long index) {
        return delegate.deleteFrom(index);
    }

    @Override
    public TxResult proveWithdrawal(Withdrawal w, long outputIndex, List<Bytes32> proof) {
        return maybeCrash("prove", delegate.proveWithdrawal(w, outputIndex, proof));
    }

    @Override
    public TxResult finalizeWithdrawal(Withdrawal w) {
        return maybeCrash("finalize", delegate.finalizeWithdrawal(w));
    }

    @Override
    public TxResult pause() {
        return delegate.pause();
    }

    @Override
    public TxResult unpause() {
        return delegate.unpause();
    }
}
