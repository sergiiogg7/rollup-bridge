package dev.rollupbridge.services;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs a {@link Service} in a loop; one failing tick is logged and retried, never fatal. */
public final class ServiceRunner implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceRunner.class);

    private final Service service;
    private final Duration interval;
    private volatile boolean running = true;

    public ServiceRunner(Service service, Duration interval) {
        this.service = service;
        this.interval = interval;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        LOG.info("[{}] started, polling every {} ms", service.name(), interval.toMillis());
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                service.tick();
            } catch (RuntimeException e) {
                LOG.warn("[{}] tick failed, will retry: {}", service.name(), e.getMessage());
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("[{}] stopped", service.name());
    }
}
