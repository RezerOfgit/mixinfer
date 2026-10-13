package com.mixinfer.exception;

import java.time.Duration;

/**
 * Thrown when a request is rejected by the rate limiter.
 * Carries the retry-after duration that the client should observe.
 */
public class RateLimitExceededException extends MixInferException {

    private final Duration retryAfter;

    public RateLimitExceededException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}