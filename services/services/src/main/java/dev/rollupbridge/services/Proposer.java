package dev.rollupbridge.services;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L1Writer;
import dev.rollupbridge.core.port.L2Reader;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts L2 outputs (withdrawal-tree roots) to L1. Stateless on purpose: every tick compares the L2 head with
 * the newest LIVE output on L1, so a restart, a deleted output or a reorg that dropped the proposal all resolve
 * themselves (the next tick simply proposes again).
 */
public final class Proposer implements Service {
    private static final Logger LOG = LoggerFactory.getLogger(Proposer.class);

    /** @param minBlocksBetween do not propose more often than this many L2 blocks apart */
    public record Config(long minBlocksBetween) {}

    private final L1Reader l1;
    private final L1Writer l1Writer;
    private final L2Reader l2;
    private final Config config;

    public Proposer(L1Reader l1, L1Writer l1Writer, L2Reader l2, Config config) {
        this.l1 = l1;
        this.l1Writer = l1Writer;
        this.l2 = l2;
        this.config = config;
    }

    @Override
    public String name() {
        return "proposer";
    }

    @Override
    public void tick() {
        long head = l2.headNumber();
        long count = l2.withdrawalCountAt(head);
        Optional<OutputRecord> last = l1.latestLiveOutput();
        if (last.isPresent()) {
            OutputRecord o = last.get();
            if (count == o.withdrawalCount() || head - o.l2BlockNumber() < config.minBlocksBetween()) {
                return; // nothing new to commit to
            }
        } else if (count == 0) {
            return;
        }
        Bytes32 root = l2.withdrawalRootAt(head);
        Bytes32 hash = l2.blockHash(head).orElseThrow(() -> new GatewayException("L2 head vanished"));
        try {
            TxResult tx = l1Writer.proposeOutput(root, hash, head, count);
            LOG.info("[proposer] proposed output for L2 block {} ({} withdrawals) root {} in tx {}", head, count, root, tx.txHash());
        } catch (GatewayException e) {
            LOG.warn("[proposer] proposal failed: {}", e.getMessage());
        }
    }
}
