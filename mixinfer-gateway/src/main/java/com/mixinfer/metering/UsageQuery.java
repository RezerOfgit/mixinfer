package com.mixinfer.metering;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Filters for the detail query.
 * All fields are optional; null means "do not filter on this field".
 */
@Value
@Builder
public class UsageQuery {

    String requestId;
    Instant from;            // inclusive, on completedAt
    Instant to;              // exclusive, on completedAt
    String model;
    String provider;
    String endpointId;
    String virtualKeyId;
    Boolean success;
    String settlementStatus;

    @Builder.Default
    int limit = 100;
    @Builder.Default
    int offset = 0;
}