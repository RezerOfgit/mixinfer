package com.mixinfer.router;

/**
 * Pricing for a specific upstream model.
 *
 * <p>Prices are in microcents (1 cent = 1,000,000 microcents) per one
 * million tokens. {@code version} is a stable identifier that lets a
 * usage record be traced back to the price table in effect at the time.
 */
public record Pricing(
        long inputPricePerMillionMicrocents,
        long outputPricePerMillionMicrocents,
        String version) {

    public Pricing {
        if (version == null || version.isBlank()) {
            version = "unversioned";
        }
        if (inputPricePerMillionMicrocents < 0 || outputPricePerMillionMicrocents < 0) {
            throw new IllegalArgumentException("prices must be non-negative");
        }
    }
}