package com.mixinfer.router;

/**
 * Pricing for a specific upstream model.
 *
 * <p>Prices are in microcents (1 cent = 1,000,000 microcents) per one
 * million tokens. {@code version} must be a stable, reproducible
 * identifier; the constructor rejects blank values so that a missing
 * version fails at startup rather than silently degrading to an
 * indistinguishable default.
 */
public record Pricing(
        long inputPricePerMillionMicrocents,
        long outputPricePerMillionMicrocents,
        String version) {

    public Pricing {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException(
                    "pricing version must be set; use a stable, reproducible identifier");
        }
        if (inputPricePerMillionMicrocents < 0 || outputPricePerMillionMicrocents < 0) {
            throw new IllegalArgumentException("prices must be non-negative");
        }
    }
}