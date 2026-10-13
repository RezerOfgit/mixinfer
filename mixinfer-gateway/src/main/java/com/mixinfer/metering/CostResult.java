package com.mixinfer.metering;

/**
 * Result of a cost computation: the estimated cost and the version of
 * the pricing table used.
 */
public record CostResult(long microcents, String pricingVersion) {
}