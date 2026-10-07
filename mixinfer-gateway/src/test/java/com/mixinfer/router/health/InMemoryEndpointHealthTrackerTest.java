package com.mixinfer.router.health;

import com.mixinfer.config.MixInferProperties;
import com.mixinfer.router.Endpoint;
import com.mixinfer.router.RouteTarget;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEndpointHealthTrackerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void should_keep_all_healthy_endpoints() {
        InMemoryEndpointHealthTracker tracker = buildTracker(Clock.fixed(T0, ZoneOffset.UTC));
        List<RouteTarget> candidates = List.of(target("a"), target("b"));

        assertThat(tracker.filter(candidates)).hasSameElementsAs(candidates);
    }

    @Test
    void should_exclude_endpoint_after_reaching_failure_threshold() {
        InMemoryEndpointHealthTracker tracker = buildTracker(Clock.fixed(T0, ZoneOffset.UTC));
        Endpoint a = endpoint("a");
        Endpoint b = endpoint("b");

        tracker.recordFailure(a);
        tracker.recordFailure(a);
        tracker.recordFailure(a);   // threshold = 3

        List<RouteTarget> filtered = tracker.filter(List.of(
                RouteTarget.builder().endpoint(a).build(),
                RouteTarget.builder().endpoint(b).build()));

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).getEndpoint().getProvider()).isEqualTo("b");
    }

    @Test
    void should_reset_on_success() {
        InMemoryEndpointHealthTracker tracker = buildTracker(Clock.fixed(T0, ZoneOffset.UTC));
        Endpoint a = endpoint("a");

        tracker.recordFailure(a);
        tracker.recordFailure(a);
        tracker.recordSuccess(a);
        tracker.recordFailure(a);
        tracker.recordFailure(a);

        // Only 2 consecutive failures since the success -> still healthy.
        List<RouteTarget> filtered = tracker.filter(List.of(
                RouteTarget.builder().endpoint(a).build()));

        assertThat(filtered).hasSize(1);
    }

    @Test
    void should_restore_endpoint_after_cooldown() {
        MutableClock clock = new MutableClock(T0);
        InMemoryEndpointHealthTracker tracker = buildTracker(clock);
        Endpoint a = endpoint("a");

        // Add a healthy second endpoint so that filtering has an effect.
        Endpoint b = endpoint("b");

        for (int i = 0; i < 3; i++) {
            tracker.recordFailure(a);
        }

        List<RouteTarget> candidates = List.of(
                RouteTarget.builder().endpoint(a).build(),
                RouteTarget.builder().endpoint(b).build());

        // A is unhealthy, B is healthy: filtered down to just B.
        assertThat(tracker.filter(candidates)).hasSize(1);

        // Advance past cooldown (default 30s) plus a margin.
        clock.advance(Duration.ofSeconds(31));

        // A is restored: both are returned.
        assertThat(tracker.filter(candidates)).hasSize(2);
    }

    @Test
    void should_return_all_when_everything_is_unhealthy() {
        InMemoryEndpointHealthTracker tracker = buildTracker(Clock.fixed(T0, ZoneOffset.UTC));
        Endpoint a = endpoint("a");
        Endpoint b = endpoint("b");

        for (int i = 0; i < 3; i++) {
            tracker.recordFailure(a);
            tracker.recordFailure(b);
        }

        List<RouteTarget> candidates = List.of(
                RouteTarget.builder().endpoint(a).build(),
                RouteTarget.builder().endpoint(b).build());

        // Even though both are marked unhealthy, the fallback returns all.
        assertThat(tracker.filter(candidates)).hasSameElementsAs(candidates);
    }

    @Test
    void should_return_all_when_disabled() {
        MixInferProperties properties = new MixInferProperties();
        properties.getHealth().setEnabled(false);
        InMemoryEndpointHealthTracker tracker =
                new InMemoryEndpointHealthTracker(properties, Clock.fixed(T0, ZoneOffset.UTC));

        Endpoint a = endpoint("a");
        for (int i = 0; i < 10; i++) {
            tracker.recordFailure(a);
        }

        List<RouteTarget> candidates = List.of(RouteTarget.builder().endpoint(a).build());
        assertThat(tracker.filter(candidates)).hasSameElementsAs(candidates);
    }

    private InMemoryEndpointHealthTracker buildTracker(Clock clock) {
        return new InMemoryEndpointHealthTracker(new MixInferProperties(), clock);
    }

    private Endpoint endpoint(String provider) {
        return Endpoint.builder()
                .provider(provider)
                .baseUrl("http://" + provider)
                .apiKey("k")
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private RouteTarget target(String provider) {
        return RouteTarget.builder().endpoint(endpoint(provider)).build();
    }

    /** Simple mutable clock for cooldown tests. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { this.now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}