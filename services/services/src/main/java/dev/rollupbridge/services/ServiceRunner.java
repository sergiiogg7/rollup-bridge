package dev.rollupbridge.services;

import dev.rollupbridge.core.metrics.MetricsRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a {@link Service} in a loop; one failing tick is logged, counted and retried, never fatal.
 *
 * <p>With a {@link MetricsRegistry} it also publishes, per service: {@code bridge_service_ticks_total{outcome}},
 * {@code bridge_service_errors_total{reason}}, {@code bridge_service_last_success_timestamp_seconds} (the signal
 * behind the "service stopped ticking" alert) and {@code bridge_service_tick_duration_seconds}.
 */
public final class ServiceRunner implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceRunner.class);

    private final Service service;
    private final Duration interval;
    private final MetricsRegistry metrics;
    private volatile boolean running = true;

    public ServiceRunner(Service service, Duration interval) {
        this(service, interval, new MetricsRegistry());
    }

    public ServiceRunner(Service service, Duration interval, MetricsRegistry metrics) {
        this.service = service;
        this.interval = interval;
        this.metrics = metrics;
        metrics.counter("bridge_service_ticks_total", "Ticks of each service by outcome.")
                .init("service", service.name(), "outcome", "ok")
                .init("service", service.name(), "outcome", "error");
        metrics.counter("bridge_service_errors_total", "Failures by service and reason (swallowed or not).");
    }

    public void stop() {
        running = false;
    }

    /** One iteration with its bookkeeping; package-private so a test can drive it without threads. */
    void tickOnce() {
        long start = System.nanoTime();
        try {
            service.tick();
            metrics.counter("bridge_service_ticks_total", "").inc("service", service.name(), "outcome", "ok");
            metrics.gauge("bridge_service_last_success_timestamp_seconds", "Unix time of the last tick that completed without throwing.")
                    .set(metrics.clock().millis() / 1000.0, "service", service.name());
        } catch (RuntimeException e) {
            LOG.warn("[{}] tick failed, will retry: {}", service.name(), e.getMessage());
            metrics.counter("bridge_service_ticks_total", "").inc("service", service.name(), "outcome", "error");
            if (!Metered.alreadyCounted(e)) { // a metered port counts its own failures; do not count them twice
                metrics.counter("bridge_service_errors_total", "").inc("service", service.name(), "reason", ErrorReasons.of(e));
            }
        } finally {
            metrics.histogram("bridge_service_tick_duration_seconds", "Duration of one tick.", 0.01, 0.05, 0.25, 1, 5, 30)
                    .observe((System.nanoTime() - start) / 1e9, "service", service.name());
        }
    }

    @Override
    public void run() {
        LOG.info("[{}] started, polling every {} ms", service.name(), interval.toMillis());
        while (running && !Thread.currentThread().isInterrupted()) {
            tickOnce();
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("[{}] stopped", service.name());
    }
}
