package com.mixinfer.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySlidingWindowRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-10-13T00:00:00Z");

    @Test
    void should_admit_up_to_limit() {
        MutableClock clock = new MutableClock(T0);
        var limiter = new InMemorySlidingWindowRateLimiter(clock);
        var policy = RateLimitPolicy.perMinute(3);

        assertThat(limiter.tryAcquire("k", policy).admitted()).isTrue();
        assertThat(limiter.tryAcquire("k", policy).admitted()).isTrue();
        assertThat(limiter.tryAcquire("k", policy).admitted()).isTrue();
        assertThat(limiter.tryAcquire("k", policy).admitted()).isFalse();
    }

    @Test
    void should_reject_with_retry_after_pointing_to_oldest_timestamp() {
        MutableClock clock = new MutableClock(T0);
        var limiter = new InMemorySlidingWindowRateLimiter(clock);
        var policy = RateLimitPolicy.perMinute(2);

        limiter.tryAcquire("k", policy);             // at T0
        clock.advance(Duration.ofSeconds(10));
        limiter.tryAcquire("k", policy);             // at T0+10s

        var decision = limiter.tryAcquire("k", policy);   // rejected
        assertThat(decision.admitted()).isFalse();
        // Oldest is T0 -> retry after 60s - 10s = 50s
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(50));
    }

    @Test
    void should_recover_after_window_slides() {
        MutableClock clock = new MutableClock(T0);
        var limiter = new InMemorySlidingWindowRateLimiter(clock);
        var policy = RateLimitPolicy.perMinute(2);

        limiter.tryAcquire("k", policy);
        limiter.tryAcquire("k", policy);
        assertThat(limiter.tryAcquire("k", policy).admitted()).isFalse();

        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("k", policy).admitted()).isTrue();
    }

    @Test
    void should_isolate_keys() {
        MutableClock clock = new MutableClock(T0);
        var limiter = new InMemorySlidingWindowRateLimiter(clock);
        var policy = RateLimitPolicy.perMinute(1);

        assertThat(limiter.tryAcquire("a", policy).admitted()).isTrue();
        assertThat(limiter.tryAcquire("a", policy).admitted()).isFalse();
        assertThat(limiter.tryAcquire("b", policy).admitted()).isTrue();
    }

    @Test
    void should_rejected_requests_not_extend_window() {
        MutableClock clock = new MutableClock(T0);
        var limiter = new InMemorySlidingWindowRateLimiter(clock);
        var policy = RateLimitPolicy.perMinute(1);

        limiter.tryAcquire("k", policy);        // admitted at T0

        // Hammer with rejections.
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(1));
            limiter.tryAcquire("k", policy);
        }

        // Advance past the original window. If rejected requests had been
        // counted, we'd still be blocked; because they aren't, we should
        // be admitted.
        clock.advance(Duration.ofSeconds(55));
        assertThat(limiter.tryAcquire("k", policy).admitted()).isTrue();
    }

    @Test
    void should_evict_expired_keys() {
        MutableClock clock = new MutableClock(T0);
        var limiter = new InMemorySlidingWindowRateLimiter(clock);

        limiter.tryAcquire("k", RateLimitPolicy.perMinute(1));
        assertThat(limiter.trackedKeyCount()).isEqualTo(1);

        clock.advance(Duration.ofHours(2));
        limiter.evictExpired(Duration.ofMinutes(30));

        assertThat(limiter.trackedKeyCount()).isZero();
    }

    @Test
    void should_admit_when_policy_is_null() {
        MutableClock clock = new MutableClock(T0);
        var limiter = new InMemorySlidingWindowRateLimiter(clock);

        assertThat(limiter.tryAcquire("k", null).admitted()).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}