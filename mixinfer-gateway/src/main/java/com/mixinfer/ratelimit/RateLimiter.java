package com.mixinfer.ratelimit;

import java.time.Duration;

/**
 * Admission control for requests against a virtual key.
 *
 * <p>Implementations must be safe under concurrent calls for the same key,
 * and must keep the "check + record" step atomic: a rejected request must
 * not extend the window that caused the rejection.
 */
public interface RateLimiter {

    /**
     * Attempts to admit one request for the given key.
     *
     * @return the decision; on rejection, includes a retry-after duration
     */
    Decision tryAcquire(String virtualKeyId, RateLimitPolicy policy);

    /** Result of an admission attempt. */
    record Decision(boolean admitted, Duration retryAfter) {

        public static Decision allow() {
            return new Decision(true, Duration.ZERO);
        }

        public static Decision reject(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }
    }
}