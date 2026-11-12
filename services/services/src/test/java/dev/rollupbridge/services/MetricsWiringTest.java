package dev.rollupbridge.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.memory.InMemoryStore;
import dev.rollupbridge.core.metrics.MetricsRegistry;
import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L1Writer;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.core.state.DepositRecord;
import dev.rollupbridge.core.state.DepositState;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests (no chain) of the metrics wiring: decorator, error classification, tick bookkeeping and the collector. */
class MetricsWiringTest {

    private static final Bytes32 ROOT = Bytes32.of("0x" + "11".repeat(32));

    @Test
    void errorsAreClassifiedIntoAFixedSetOfReasons() {
        assertThat(ErrorReasons.of(new GatewayException("reverted: 0x1234", "0x1234", null))).isEqualTo(ErrorReasons.TX_REVERTED);
        assertThat(ErrorReasons.of(new GatewayException("rpc transport error: refused"))).isEqualTo(ErrorReasons.RPC_TRANSPORT);
        assertThat(ErrorReasons.of(new GatewayException("tx 0xab not mined in time"))).isEqualTo(ErrorReasons.TX_TIMEOUT);
        assertThat(ErrorReasons.of(new GatewayException("rpc error -32000: nonce too low"))).isEqualTo(ErrorReasons.RPC_ERROR);
        assertThat(ErrorReasons.of(new IllegalStateException("illegal transition Done -> Seen"))).isEqualTo(ErrorReasons.STATE_MACHINE);
        assertThat(ErrorReasons.of(new NullPointerException())).isEqualTo(ErrorReasons.INTERNAL);
    }

    @Test
    void meteredPortCountsTransactionsAndDomainEventsAndFailures() {
        var m = new MetricsRegistry();
        L1Writer fake = stub(L1Writer.class, (method, args) -> switch (method) {
            case "proposeOutput" -> new TxResult("0x1", true, 5, List.of(), null);
            case "challengeOutput" -> new TxResult("0x2", false, 6, List.of(), null);
            case "pause" -> throw new GatewayException("rpc transport error: refused");
            default -> throw new UnsupportedOperationException(method);
        });
        L1Writer metered = Metered.wrap(L1Writer.class, fake, m, "proposer");

        metered.proposeOutput(ROOT, ROOT, 1, 0);
        metered.challengeOutput(0);
        assertThatThrownBy(metered::pause).isInstanceOf(GatewayException.class).hasMessageContaining("refused");

        assertThat(m.counter("bridge_outputs_proposed_total", "").value()).isEqualTo(1);
        assertThat(m.counter("bridge_outputs_challenged_total", "").value()).isZero(); // mined but failed: not counted
        assertThat(m.counter("bridge_transactions_total", "").value("service", "proposer", "op", "challengeOutput", "outcome", "failed")).isEqualTo(1);
        assertThat(m.counter("bridge_transactions_total", "").value("service", "proposer", "op", "pause", "outcome", "error")).isEqualTo(1);
        assertThat(m.counter("bridge_service_errors_total", "").value("service", "proposer", "reason", "rpc_transport")).isEqualTo(1);
    }

    @Test
    void aFailureCountedByAPortIsNotCountedAgainByTheRunner() {
        var m = new MetricsRegistry();
        L1Reader failing = Metered.wrap(L1Reader.class, stub(L1Reader.class, (method, args) -> {
            throw new GatewayException("rpc transport error: down");
        }), m, "challenger");
        var runner = new ServiceRunner(new Service() {
            @Override public String name() { return "challenger"; }
            @Override public void tick() { failing.headNumber(); }
        }, Duration.ofSeconds(1), m);

        runner.tickOnce();

        assertThat(m.counter("bridge_service_errors_total", "").value("service", "challenger", "reason", "rpc_transport")).isEqualTo(1);
        assertThat(m.counter("bridge_service_ticks_total", "").value("service", "challenger", "outcome", "error")).isEqualTo(1);
        assertThat(m.counter("bridge_service_ticks_total", "").value("service", "challenger", "outcome", "ok")).isZero();
    }

    @Test
    void anUnmeteredFailureIsCountedByTheRunnerAndASuccessStampsTheTime() {
        var m = new MetricsRegistry(java.time.Clock.fixed(java.time.Instant.ofEpochSecond(1_700_000_000), java.time.ZoneOffset.UTC));
        boolean[] fail = {true};
        var runner = new ServiceRunner(new Service() {
            @Override public String name() { return "x"; }
            @Override public void tick() { if (fail[0]) { throw new IllegalStateException("boom"); } }
        }, Duration.ofSeconds(1), m);

        runner.tickOnce();
        assertThat(m.counter("bridge_service_errors_total", "").value("service", "x", "reason", "internal")).isEqualTo(1);
        assertThat(m.gauge("bridge_service_last_success_timestamp_seconds", "").value("service", "x")).isNaN();

        fail[0] = false;
        runner.tickOnce();
        assertThat(m.gauge("bridge_service_last_success_timestamp_seconds", "").value("service", "x")).isEqualTo(1_700_000_000);
        assertThat(m.histogram("bridge_service_tick_duration_seconds", "", 1).count("service", "x")).isEqualTo(2);
    }

    @Test
    void collectorPublishesStatesWindowsAndLagAndSurvivesAnRpcOutage() {
        var store = new InMemoryStore();
        store.saveDeposit(new DepositRecord(0, "0xa", BigInteger.ONE, 10, ROOT, new DepositState.Seen()));
        store.saveDeposit(new DepositRecord(1, "0xa", BigInteger.ONE, 10, ROOT, new DepositState.Confirmed()));
        store.saveDeposit(new DepositRecord(2, "0xa", BigInteger.ONE, 10, ROOT, new DepositState.Done("0xdead")));
        store.setCursor(DepositRelayer.CURSOR, 95);
        boolean[] outage = {false};
        L1Reader l1 = stub(L1Reader.class, (method, args) -> {
            if (outage[0]) {
                throw new GatewayException("rpc transport error: refused");
            }
            return switch (method) {
                case "headNumber" -> 100L;
                case "headTimestamp" -> 1_000L;
                case "outputCount" -> 2L;
                case "challengeWindow" -> 600L;
                case "isPaused" -> false;
                case "output" -> new OutputRecord((Long) args[0], ROOT, ROOT, 7, 0,
                        (Long) args[0] == 0 ? 0 : 900, (Long) args[0] == 0 ? OutputStatus.CHALLENGED : OutputStatus.PROPOSED,
                        "0xp", "0xc", 0, false);
                default -> throw new UnsupportedOperationException(method);
            };
        });
        L2Reader l2 = stub(L2Reader.class, (method, args) -> 50L);
        var m = new MetricsRegistry();
        var collector = new BridgeCollector(l1, l2, store, store, store, m);

        collector.collect();

        assertThat(m.gauge("bridge_deposits_by_state", "").value("state", "Seen")).isEqualTo(1);
        assertThat(m.gauge("bridge_deposits_by_state", "").value("state", "Done")).isEqualTo(1);
        assertThat(m.gauge("bridge_deposits_pending", "").value()).isEqualTo(2);
        assertThat(m.gauge("bridge_l1_confirmations_waiting", "").value()).isEqualTo(1);
        assertThat(m.gauge("bridge_cursor_lag_blocks", "").value("chain", "l1")).isEqualTo(5);
        assertThat(m.gauge("bridge_outputs_in_window", "").value()).isEqualTo(1);
        assertThat(m.gauge("bridge_open_output_min_window_remaining_seconds", "").value()).isEqualTo(500); // 900 + 600 - 1000
        assertThat(m.gauge("bridge_challenger_unchecked_outputs", "").value()).isEqualTo(1);
        assertThat(m.gauge("bridge_unchecked_output_min_window_remaining_seconds", "").value()).isEqualTo(500);
        assertThat(m.gauge("bridge_challenge_window_seconds", "").value()).isEqualTo(600);
        assertThat(m.gauge("bridge_outputs", "").value("status", "challenged")).isEqualTo(1);

        store.markChecked(1, ROOT);
        collector.collect();
        assertThat(m.gauge("bridge_unchecked_output_min_window_remaining_seconds", "").value()).isNaN(); // nothing unchecked
        outage[0] = true; // must not throw; counts the failure and keeps serving the last values
        collector.collect();
        assertThat(m.counter("bridge_collector_errors_total", "").value("section", "l1_head")).isEqualTo(1);
        assertThat(m.counter("bridge_collector_errors_total", "").value("section", "outputs")).isEqualTo(1);
        assertThat(m.gauge("bridge_chain_head_block", "").value("chain", "l1")).isEqualTo(100);
    }

    // ------------------------------------------------------------------------------------------

    private interface Handler {
        Object handle(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> port, Handler h) {
        return (T) Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, (p, method, args) -> h.handle(method.getName(), args));
    }
}
