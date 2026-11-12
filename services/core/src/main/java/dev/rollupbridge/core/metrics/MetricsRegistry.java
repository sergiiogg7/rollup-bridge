package dev.rollupbridge.core.metrics;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny, dependency-free metrics registry that renders the Prometheus text exposition format (version 0.0.4).
 * It performs no I/O: serving the text over HTTP is the job of the {@code app} module. (Analogy: the
 * {@code MeterRegistry} of Micrometer, reduced to counters, gauges and histograms.)
 *
 * <p>Label values are passed as alternating {@code name, value} strings. Registering the same metric name twice
 * returns the existing instance, so several services can share one registry.
 */
public final class MetricsRegistry {
    private final Clock clock;
    private final Map<String, Family> families = new ConcurrentHashMap<>();

    public MetricsRegistry() {
        this(Clock.systemUTC());
    }

    public MetricsRegistry(Clock clock) {
        this.clock = clock;
    }

    /** The time source of this registry (tests pass a fixed clock). */
    public Clock clock() {
        return clock;
    }

    public Counter counter(String name, String help) {
        return (Counter) families.computeIfAbsent(name, n -> new Counter(n, help));
    }

    public Gauge gauge(String name, String help) {
        return (Gauge) families.computeIfAbsent(name, n -> new Gauge(n, help));
    }

    public Histogram histogram(String name, String help, double... upperBounds) {
        return (Histogram) families.computeIfAbsent(name, n -> new Histogram(n, help, upperBounds));
    }

    /** Prometheus text format of every metric, families sorted by name, samples sorted by labels. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(families).values().forEach(f -> f.render(sb));
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------

    private abstract static class Family {
        final String name;
        final String help;

        Family(String name, String help) {
            this.name = name;
            this.help = help;
        }

        abstract String type();

        abstract void samples(StringBuilder sb);

        final void render(StringBuilder sb) {
            sb.append("# HELP ").append(name).append(' ').append(help.replace("\\", "\\\\").replace("\n", "\\n")).append('\n');
            sb.append("# TYPE ").append(name).append(' ').append(type()).append('\n');
            samples(sb);
        }
    }

    /** Canonical label string {@code {a="1",b="2"}} (sorted by label name), or the empty string. */
    static String labelString(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("labels must be name/value pairs");
        }
        if (kv.length == 0) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            sorted.put(kv[i], kv[i + 1]);
        }
        StringBuilder sb = new StringBuilder("{");
        sorted.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(k).append("=\"").append(v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"');
        });
        return sb.append('}').toString();
    }

    static String number(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "+Inf" : "-Inf";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    /** Monotonic counter. Name should end in {@code _total}. */
    public static final class Counter extends Family {
        private final Map<String, Double> values = new ConcurrentHashMap<>();

        private Counter(String name, String help) {
            super(name, help);
        }

        @Override
        String type() {
            return "counter";
        }

        public void inc(String... labels) {
            add(1, labels);
        }

        public void add(double amount, String... labels) {
            if (amount < 0) {
                throw new IllegalArgumentException("a counter never decreases");
            }
            values.merge(labelString(labels), amount, Double::sum);
        }

        /** Makes the series visible with value 0 before the first event (so rate() and alerts see it). */
        public Counter init(String... labels) {
            values.putIfAbsent(labelString(labels), 0.0);
            return this;
        }

        public double value(String... labels) {
            return values.getOrDefault(labelString(labels), 0.0);
        }

        @Override
        void samples(StringBuilder sb) {
            new TreeMap<>(values).forEach((l, v) -> sb.append(name).append(l).append(' ').append(number(v)).append('\n'));
        }
    }

    /** A value that goes up and down. */
    public static final class Gauge extends Family {
        private final Map<String, Double> values = new ConcurrentHashMap<>();

        private Gauge(String name, String help) {
            super(name, help);
        }

        @Override
        String type() {
            return "gauge";
        }

        public void set(double value, String... labels) {
            values.put(labelString(labels), value);
        }

        /** Removes one series (used for values that are "absent" rather than zero). */
        public void remove(String... labels) {
            values.remove(labelString(labels));
        }

        public double value(String... labels) {
            return values.getOrDefault(labelString(labels), Double.NaN);
        }

        @Override
        void samples(StringBuilder sb) {
            new TreeMap<>(values).forEach((l, v) -> sb.append(name).append(l).append(' ').append(number(v)).append('\n'));
        }
    }

    /** Cumulative histogram with fixed upper bounds (a {@code +Inf} bucket is implicit). */
    public static final class Histogram extends Family {
        private final double[] bounds;
        private final Map<String, Series> series = new ConcurrentHashMap<>();

        private Histogram(String name, String help, double[] bounds) {
            super(name, help);
            this.bounds = bounds.clone();
        }

        @Override
        String type() {
            return "histogram";
        }

        private static final class Series {
            long[] counts;
            double sum;
            long count;
        }

        public void observe(double value, String... labels) {
            Series s = series.computeIfAbsent(labelString(labels), k -> {
                Series n = new Series();
                n.counts = new long[bounds.length];
                return n;
            });
            synchronized (s) {
                for (int i = 0; i < bounds.length; i++) {
                    if (value <= bounds[i]) {
                        s.counts[i]++;
                    }
                }
                s.sum += value;
                s.count++;
            }
        }

        public long count(String... labels) {
            Series s = series.get(labelString(labels));
            return s == null ? 0 : s.count;
        }

        @Override
        void samples(StringBuilder sb) {
            new TreeMap<>(series).forEach((labels, s) -> {
                synchronized (s) {
                    for (int i = 0; i < bounds.length; i++) {
                        sb.append(name).append("_bucket").append(withLe(labels, number(bounds[i]))).append(' ').append(s.counts[i]).append('\n');
                    }
                    sb.append(name).append("_bucket").append(withLe(labels, "+Inf")).append(' ').append(s.count).append('\n');
                    sb.append(name).append("_sum").append(labels).append(' ').append(number(s.sum)).append('\n');
                    sb.append(name).append("_count").append(labels).append(' ').append(s.count).append('\n');
                }
            });
        }

        private static String withLe(String labels, String le) {
            List<String> parts = new ArrayList<>();
            if (!labels.isEmpty()) {
                parts.add(labels.substring(1, labels.length() - 1));
            }
            parts.add("le=\"" + le + "\"");
            return "{" + String.join(",", parts) + "}";
        }
    }
}
