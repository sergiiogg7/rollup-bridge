package dev.rollupbridge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rollupbridge.core.metrics.MetricsRegistry;
import org.junit.jupiter.api.Test;

class MetricsRegistryTest {

    @Test
    void rendersCountersGaugesAndHistogramsInPrometheusTextFormat() {
        var m = new MetricsRegistry();
        m.counter("bridge_errors_total", "Errors.").add(2, "service", "proposer", "reason", "rpc");
        m.counter("bridge_errors_total", "ignored second help").inc("service", "proposer", "reason", "rpc");
        m.gauge("bridge_pending", "Pending.").set(4);
        var h = m.histogram("bridge_latency_seconds", "Latency.", 1, 10);
        h.observe(0.5);
        h.observe(7);
        h.observe(70);

        assertThat(m.render()).isEqualTo("""
                # HELP bridge_errors_total Errors.
                # TYPE bridge_errors_total counter
                bridge_errors_total{reason="rpc",service="proposer"} 3
                # HELP bridge_latency_seconds Latency.
                # TYPE bridge_latency_seconds histogram
                bridge_latency_seconds_bucket{le="1"} 1
                bridge_latency_seconds_bucket{le="10"} 2
                bridge_latency_seconds_bucket{le="+Inf"} 3
                bridge_latency_seconds_sum 77.5
                bridge_latency_seconds_count 3
                # HELP bridge_pending Pending.
                # TYPE bridge_pending gauge
                bridge_pending 4
                """);
    }

    @Test
    void sameNameReturnsTheSameFamilyAndInitMakesASeriesVisible() {
        var m = new MetricsRegistry();
        assertThat(m.counter("c_total", "c")).isSameAs(m.counter("c_total", "c"));
        m.counter("c_total", "c").init("k", "v");
        assertThat(m.render()).contains("c_total{k=\"v\"} 0");
        assertThat(m.counter("c_total", "c").value("k", "v")).isZero();
    }

    @Test
    void labelValuesAreEscapedAndLabelsAreSorted() {
        var m = new MetricsRegistry();
        m.gauge("g", "g").set(1.5, "b", "say \"hi\"\n", "a", "x\\y");
        assertThat(m.render()).contains("g{a=\"x\\\\y\",b=\"say \\\"hi\\\"\\n\"} 1.5");
    }

    @Test
    void gaugesCanBeRemovedAndReadBack() {
        var m = new MetricsRegistry();
        var g = m.gauge("g", "g");
        g.set(3, "s", "a");
        assertThat(g.value("s", "a")).isEqualTo(3);
        g.remove("s", "a");
        assertThat(g.value("s", "a")).isNaN();
        assertThat(m.render()).doesNotContain("g{");
    }

    @Test
    void rejectsNegativeIncrementsAndOddLabelLists() {
        var m = new MetricsRegistry();
        assertThatThrownBy(() -> m.counter("c_total", "c").add(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.counter("c_total", "c").inc("only-a-name")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void specialFloatingPointValuesUseTheTextFormatSpelling() {
        var m = new MetricsRegistry();
        m.gauge("g", "g").set(Double.NaN, "k", "nan");
        m.gauge("g", "g").set(Double.POSITIVE_INFINITY, "k", "pinf");
        m.gauge("g", "g").set(Double.NEGATIVE_INFINITY, "k", "ninf");
        assertThat(m.render()).contains("g{k=\"nan\"} NaN").contains("g{k=\"pinf\"} +Inf").contains("g{k=\"ninf\"} -Inf");
    }
}
