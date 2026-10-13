package com.mixinfer.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodic cleanup for rate limiter state.
 *
 * <p>Runs on a fixed cadence with a window larger than any configured
 * policy, so active keys are never evicted while in use.
 */
@Component
public class RateLimiterCleanupTask {

    /** Conservative upper bound; adjust if a longer window is introduced. */
    private static final Duration MAX_WINDOW = Duration.ofHours(1);

    private final InMemorySlidingWindowRateLimiter limiter;

    public RateLimiterCleanupTask(InMemorySlidingWindowRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Scheduled(fixedDelayString = "PT5M")
    public void evictExpired() {
        limiter.evictExpired(MAX_WINDOW);
    }
}