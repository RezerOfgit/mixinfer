package com.mixinfer.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter with per-key state in memory.
 *
 * <p>Window semantics: a request at time {@code now} is admitted iff fewer
 * than {@code policy.limit()} timestamps fall in {@code (now - window, now]}.
 * Rejected requests are not recorded, so a client that hammers the endpoint
 * cannot extend its own window.
 *
 * <p>All state mutations for a given key go through
 * {@link ConcurrentHashMap#compute}. This makes admission and cleanup
 * mutually exclusive per key, which the ADR requires.
 */
@Component
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    private final Clock clock;
    private final ConcurrentHashMap<String, KeyBucket> buckets = new ConcurrentHashMap<>();

    public InMemorySlidingWindowRateLimiter() {
        this(Clock.systemUTC());
    }

    /** Visible for testing. */
    InMemorySlidingWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String virtualKeyId, RateLimitPolicy policy) {
        if (policy == null) {
            return Decision.allow();
        }

        long now = clock.millis();
        long windowMillis = policy.window().toMillis();
        long cutoff = now - windowMillis;

        Decision[] holder = new Decision[1];
        buckets.compute(virtualKeyId, (key, existing) -> {
            KeyBucket bucket = existing == null ? new KeyBucket() : existing;

            // Evict expired timestamps first.
            while (!bucket.timestamps.isEmpty() && bucket.timestamps.peekFirst() <= cutoff) {
                bucket.timestamps.pollFirst();
            }

            if (bucket.timestamps.size() >= policy.limit()) {
                long oldest = bucket.timestamps.peekFirst();
                long waitMillis = Math.max((oldest + windowMillis) - now, 0);
                holder[0] = Decision.reject(Duration.ofMillis(waitMillis));
                return bucket;
            }

            bucket.timestamps.addLast(now);
            holder[0] = Decision.allow();
            return bucket;
        });
        return holder[0];
    }

    /**
     * Removes buckets that no longer hold any timestamp within their
     * window. Intended to be invoked periodically; safe to invoke at any
     * time, including concurrently with admission.
     */
    public void evictExpired(Duration maxWindow) {
        long now = clock.millis();
        long cutoff = now - maxWindow.toMillis();
        buckets.forEach((key, ignored) ->
                buckets.computeIfPresent(key, (k, bucket) -> {
                    while (!bucket.timestamps.isEmpty()
                            && bucket.timestamps.peekFirst() <= cutoff) {
                        bucket.timestamps.pollFirst();
                    }
                    return bucket.timestamps.isEmpty() ? null : bucket;
                }));
    }

    /** Visible for testing. */
    int trackedKeyCount() {
        return buckets.size();
    }

    private static final class KeyBucket {
        final Deque<Long> timestamps = new ArrayDeque<>();
    }
}