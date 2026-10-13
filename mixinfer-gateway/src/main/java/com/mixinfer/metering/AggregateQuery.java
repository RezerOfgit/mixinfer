package com.mixinfer.metering;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Parameters for the aggregate query.
 *
 * <p>{@code groupBy} selects the primary dimension; {@code window} optionally
 * splits each group into time buckets.
 */
@Value
@Builder
public class AggregateQuery {

    public static final String GROUP_BY_MODEL = "model";
    public static final String GROUP_BY_PROVIDER = "provider";
    public static final String GROUP_BY_ENDPOINT = "endpointId";
    public static final String GROUP_BY_VIRTUAL_KEY = "virtualKeyId";

    public static final String WINDOW_HOUR = "hour";
    public static final String WINDOW_DAY = "day";
    public static final String WINDOW_MONTH = "month";

    Instant from;             // required
    Instant to;               // required
    String groupBy;           // required
    String window;            // nullable

    String model;             // optional filter
    String provider;          // optional filter
    String virtualKeyId;      // optional filter
}