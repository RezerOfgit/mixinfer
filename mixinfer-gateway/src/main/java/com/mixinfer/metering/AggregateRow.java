package com.mixinfer.metering;

import lombok.Builder;
import lombok.Value;

/**
 * One row of an aggregate result.
 *
 * <p>{@code key} is the value of the {@code groupBy} dimension.
 * {@code windowKey} is the time bucket (e.g. {@code 2026-10-13} for day),
 * or null when no window was requested.
 *
 * <p>{@code unpricedCount} is the number of requests in this bucket whose
 * {@code costMicrocents} is null. The cost sum intentionally excludes
 * those records; consumers must not interpret the sum as covering every
 * request in the bucket.
 */
@Value
@Builder
public class AggregateRow {

    String key;
    String windowKey;

    long requestCount;
    long successCount;
    long attemptCount;

    long inputTokens;
    long outputTokens;
    long costMicrocents;

    /** Number of requests in this bucket with null cost. */
    long unpricedCount;
}