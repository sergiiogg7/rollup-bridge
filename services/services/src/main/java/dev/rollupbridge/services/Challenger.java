package dev.rollupbridge.services;

import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.port.BridgeStore;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L1Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches outputs inside their challenge window, re-derives each one from L2 with {@link OutputVerifier} and
 * challenges (with a bond) any that do not match. This is the actor that makes the system "optimistic but safe":
 * it only has to be online and honest ONCE per bad output, inside the window.
 */
public final class Challenger implements Service {
    private static final Logger LOG = LoggerFactory.getLogger(Challenger.class);
    /** Only the newest outputs can still be inside a window; older ones are skipped to keep a tick cheap. */
    private static final long LOOKBACK_OUTPUTS = 64;

    private final L1Reader l1;
    private final L1Writer l1Writer;
    private final OutputVerifier verifier;
    private final BridgeStore store;

    public Challenger(L1Reader l1, L1Writer l1Writer, OutputVerifier verifier, BridgeStore store) {
        this.l1 = l1;
        this.l1Writer = l1Writer;
        this.verifier = verifier;
        this.store = store;
    }

    @Override
    public String name() {
        return "challenger";
    }

    @Override
    public void tick() {
        long n = l1.outputCount();
        long window = l1.challengeWindow();
        long now = l1.headTimestamp();
        for (long i = Math.max(0, n - LOOKBACK_OUTPUTS); i < n; i++) {
            OutputRecord o = l1.output(i);
            if (o.status() != OutputStatus.PROPOSED || store.isChecked(i, o.withdrawalRoot())) {
                continue;
            }
            if (now >= o.proposedAt() + window) {
                continue; // window closed: nothing can be done any more
            }
            OutputVerifier.Verdict verdict = verifier.verify(o);
            switch (verdict) {
                case OutputVerifier.Verdict.Valid v -> {
                    LOG.info("[challenger] output #{} verified against L2", i);
                    store.markChecked(i, o.withdrawalRoot());
                }
                case OutputVerifier.Verdict.Invalid bad -> {
                    LOG.warn("[challenger] output #{} is WRONG: {}", i, bad.reasons());
                    try {
                        l1Writer.challengeOutput(i);
                        LOG.warn("[challenger] challenged output #{}", i);
                        store.markChecked(i, o.withdrawalRoot());
                    } catch (GatewayException e) {
                        LOG.warn("[challenger] challenge of output #{} failed: {}", i, e.getMessage());
                    }
                }
            }
        }
    }
}
