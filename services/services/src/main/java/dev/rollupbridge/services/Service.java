package dev.rollupbridge.services;

/**
 * One polling worker. {@link #tick()} must be idempotent: it reads the chain and the store, does whatever is
 * next, and can be called again at any time, including after a crash half-way through a previous call.
 * (Analogy: a Spring {@code @Scheduled} method that is safe to run twice.)
 */
public interface Service {
    String name();

    void tick();
}
