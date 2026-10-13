package com.mixinfer.metering;

import lombok.Builder;
import lombok.Value;

/**
 * One row of an aggregate result.
 *
 * <p>{@code key} is the value of the {@code groupBy} dimension.
 * {@code windowKey} is the time bucket (e.g. {@code 2026-10-13} for day),
 * or null when no window was requested.
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
}