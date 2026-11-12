package dev.rollupbridge.services;

import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L1Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The guardian: the single trusted party of the MVP. It resolves challenges (after re-checking the output with
 * its own verifier) and can pause the L1 bridge. A real system would replace {@code resolveChallenge} by an
 * on-chain dispute game; the {@code BisectionGame} stretch shows that mechanism at toy scale.
 */
public final class Guardian implements Service {
    private static final Logger LOG = LoggerFactory.getLogger(Guardian.class);

    private final L1Reader l1;
    private final L1Writer l1Writer;
    private final OutputVerifier verifier;

    public Guardian(L1Reader l1, L1Writer l1Writer, OutputVerifier verifier) {
        this.l1 = l1;
        this.l1Writer = l1Writer;
        this.verifier = verifier;
    }

    @Override
    public String name() {
        return "guardian";
    }

    /** Resolves every challenged output. */
    @Override
    public void tick() {
        long n = l1.outputCount();
        for (long i = 0; i < n; i++) {
            if (l1.output(i).status() == OutputStatus.CHALLENGED) {
                resolve(i);
            }
        }
    }

    /** Re-derives output {@code index} from L2 and rules on its challenge. Returns true if the proposer was wrong. */
    public boolean resolve(long index) {
        OutputRecord o = l1.output(index);
        boolean proposerWrong = verifier.verify(o) instanceof OutputVerifier.Verdict.Invalid;
        LOG.warn("[guardian] ruling on output #{}: {}", index, proposerWrong ? "PROPOSER WAS WRONG" : "challenger was wrong");
        try {
            l1Writer.resolveChallenge(index, proposerWrong);
        } catch (GatewayException e) {
            LOG.warn("[guardian] resolve failed: {}", e.getMessage());
            throw e;
        }
        return proposerWrong;
    }

    public void pause() {
        l1Writer.pause();
        LOG.warn("[guardian] L1 bridge PAUSED");
    }

    public void unpause() {
        l1Writer.unpause();
        LOG.warn("[guardian] L1 bridge unpaused");
    }
}
