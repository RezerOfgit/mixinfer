package com.mixinfer.ratelimit;

import java.time.Duration;

/**
 * A rate limit policy: at most {@code limit} requests in any sliding
 * window of {@code window}.
 */
public record RateLimitPolicy(int limit, Duration window) {

    public RateLimitPolicy {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }

    /** Convenience for "N requests per minute". */
    public static RateLimitPolicy perMinute(int requests) {
        return new RateLimitPolicy(requests, Duration.ofMinutes(1));
    }
}