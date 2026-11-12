package dev.rollupbridge.e2e;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.rollupbridge.core.port.BridgeStore;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L1Writer;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.services.Challenger;
import dev.rollupbridge.services.DepositRelayer;
import dev.rollupbridge.services.Guardian;
import dev.rollupbridge.services.OutputVerifier;
import dev.rollupbridge.services.Proposer;
import dev.rollupbridge.services.WithdrawalRelayer;
import dev.rollupbridge.testkit.Accounts;
import dev.rollupbridge.testkit.Anvil;
import dev.rollupbridge.testkit.Bridge;
import java.math.BigInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared set-up for the end-to-end scenarios: two native Anvil processes (L1 chain id 31337, L2 chain id 901)
 * started once per JVM, and a FRESH deployment of all contracts for every test, so tests never see each other's
 * state. Skipped (not failed) when no {@code anvil} binary is on the PATH.
 */
abstract class E2eBase {
    protected static final BigInteger ETH = Bridge.ETH;

    private static Anvil l1Node;
    private static Anvil l2Node;

    protected Bridge bridge;

    private static synchronized void ensureChains() {
        if (l1Node == null) {
            l1Node = Anvil.start(0, 31337);
            l2Node = Anvil.start(0, 901);
        }
    }

    @BeforeEach
    void deployFreshBridge() {
        assumeTrue(Anvil.isAvailable(), "anvil not found on PATH: end-to-end tests skipped");
        ensureChains();
        bridge = Bridge.deploy(l1Node, l2Node, Bridge.Params.defaults());
    }

    // ----- service factories (each role gets its own key, like in production) -----

    protected L1Reader l1Read() {
        return bridge.l1(null);
    }

    protected L2Reader l2Read() {
        return bridge.l2(null);
    }

    protected DepositRelayer depositRelayer(BridgeStore store, long confirmations) {
        return new DepositRelayer(
                l1Read(), l2Read(), bridge.l2(Accounts.RELAYER), store,
                new DepositRelayer.Config(confirmations, bridge.l1DeployBlock, 20));
    }

    protected Proposer proposer() {
        return new Proposer(l1Read(), bridge.l1(Accounts.PROPOSER), l2Read(), new Proposer.Config(0));
    }

    protected WithdrawalRelayer withdrawalRelayer(BridgeStore store) {
        return withdrawalRelayer(store, bridge.l1(Accounts.RELAYER));
    }

    protected WithdrawalRelayer withdrawalRelayer(BridgeStore store, L1Writer writer) {
        return new WithdrawalRelayer(l1Read(), writer, l2Read(), store);
    }

    protected Challenger challenger(BridgeStore store) {
        return new Challenger(l1Read(), bridge.l1(Accounts.CHALLENGER), new OutputVerifier(l2Read()), store);
    }

    protected Guardian guardian() {
        return new Guardian(l1Read(), bridge.l1(Accounts.GUARDIAN), new OutputVerifier(l2Read()));
    }

    // ----- scenario helpers -----

    /** Alice deposits and the deposit is relayed to L2 (relayer with zero confirmations on a quiet chain). */
    protected void fundAliceOnL2(BigInteger wei, BridgeStore store) {
        bridge.deposit(Accounts.ALICE, Accounts.ALICE, wei);
        depositRelayer(store, 0).tick();
    }

    /** Fast-forwards the L1 clock past the challenge window. */
    protected void passChallengeWindow() {
        bridge.l1Node.increaseTime(bridge.params.challengeWindow() + 1);
    }

    protected static void eventually(String what, BooleanSupplier condition, Runnable step) {
        for (int i = 0; i < 20; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            step.run();
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("condition not reached: " + what);
        }
    }
}
