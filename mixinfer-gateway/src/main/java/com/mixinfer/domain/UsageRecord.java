package com.mixinfer.domain;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * A settlement record for a single client request.
 *
 * <p>One client request produces at most one UsageRecord, even if it went
 * through multiple upstream attempts during failover. {@code attemptCount}
 * and {@code attempts} describe those attempts without inflating the
 * client-facing request count.
 */
@Value
@Builder
public class UsageRecord {

    /** Request identifier. Correlates logs, usage, and audit. */
    String requestId;

    /** Virtual key id (governance subject). Never the plaintext secret. */
    String virtualKeyId;

    /** Logical model requested by the client. */
    String model;

    /** Logical provider that eventually handled the request. Nullable on failure. */
    String provider;

    /** Endpoint identifier that handled the request. Nullable. */
    String endpointId;

    /** Model identifier reported by the upstream. Nullable. */
    String upstreamModel;

    /** Timestamp when the request started. */
    Instant startedAt;

    /** Timestamp when the request completed (successfully or not). */
    Instant completedAt;

    /** Usage reported by the upstream. Nullable. */
    LlmUsage providerReported;

    /** Estimated cost in microcents (1 cent = 1,000,000 microcents). Nullable. */
    Long costMicrocents;

    /** Pricing version used for this settlement. Nullable. */
    String pricingVersion;

    /** Confidence level of the usage numbers. */
    UsageSettlementStatus settlementStatus;

    /** Where the usage numbers came from. */
    UsageSource usageSource;

    /** Reason for non-FINAL settlement. Nullable. */
    String reason;

    /** Whether the client request succeeded. */
    boolean success;

    /** Number of upstream attempts made during failover. */
    int attemptCount;

    /** Per-attempt diagnostic detail, JSON-encoded. Nullable. */
    String attempts;
}