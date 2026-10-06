package com.mixinfer.domain;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * A settlement record for a single request.
 *
 * <p>Usage is stateful: the record may be created when the stream is
 * still incomplete and finalized later. {@code settlementStatus} and
 * {@code usageSource} express the confidence in the numbers.
 */
@Value
@Builder
public class UsageRecord {

    /** Request identifier. Used to correlate logs, usage, and audit. */
    String requestId;

    /** Logical model name. */
    String model;

    /** Logical provider name. */
    String provider;

    /** Timestamp when the request started. */
    Instant startedAt;

    /** Timestamp when the request ended (successfully or not). */
    Instant completedAt;

    /**
     * Usage reported by the upstream. May be null.
     * Preserved as-is; MixInfer does not fabricate this.
     */
    LlmUsage providerReported;

    /** Confidence level of the usage numbers. */
    UsageSettlementStatus settlementStatus;

    /** Where the numbers came from. */
    UsageSource usageSource;

    /**
     * Reason for non-FINAL settlement, e.g. "client_disconnected" or
     * "upstream_stream_error". Null on a clean finish.
     */
    String reason;
}