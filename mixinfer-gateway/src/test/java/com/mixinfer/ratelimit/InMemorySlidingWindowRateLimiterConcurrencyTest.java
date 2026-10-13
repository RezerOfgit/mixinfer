package com.mixinfer.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySlidingWindowRateLimiterConcurrencyTest {

    @Test
    void should_not_exceed_limit_under_concurrency() throws Exception {
        var limiter = new InMemorySlidingWindowRateLimiter();
        var policy = RateLimitPolicy.perMinute(100);

        int threads = 50;
        int perThread = 10;      // 500 total attempts, limit is 100
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger admitted = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.tryAcquire("k", policy).admitted()) {
                            admitted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(admitted.get()).isEqualTo(100);
    }
}