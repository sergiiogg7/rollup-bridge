package dev.rollupbridge.core.model;

/** What {@code L1Bridge.proofs(leaf)} returns. */
public record ProofInfo(boolean exists, long outputIndex, long provenAt) {
    public static final ProofInfo NONE = new ProofInfo(false, 0, 0);
}
