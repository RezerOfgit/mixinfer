package com.mixinfer.router.health;

import com.mixinfer.config.MixInferProperties;
import com.mixinfer.router.Endpoint;
import com.mixinfer.router.RouteTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-instance implementation of {@link EndpointHealthTracker}.
 *
 * <p>State is keyed by provider name. Endpoints are considered the same
 * across requests as long as they belong to the same configured provider.
 *
 * <p>Not shared across instances: multiple MixInfer processes maintain
 * independent health state. This is intentional in V0.3 (no distributed
 * coordination yet).
 */
@Component
public class InMemoryEndpointHealthTracker implements EndpointHealthTracker {

    private final MixInferProperties.HealthConfig config;
    private final Clock clock;
    private final ConcurrentHashMap<String, HealthState> states = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryEndpointHealthTracker(MixInferProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Visible for testing. */
    InMemoryEndpointHealthTracker(MixInferProperties properties, Clock clock) {
        this.config = properties.getHealth();
        this.clock = clock;
    }

    @Override
    public List<RouteTarget> filter(List<RouteTarget> candidates) {
        if (!config.isEnabled() || candidates.isEmpty()) {
            return candidates;
        }

        Instant now = clock.instant();
        Duration cooldown = Duration.ofSeconds(config.getCooldownSeconds());

        List<RouteTarget> available = new ArrayList<>(candidates.size());
        for (RouteTarget target : candidates) {
            if (isAvailable(target.getEndpoint(), now, cooldown)) {
                available.add(target);
            }
        }

        // Fallback: never return empty if input is non-empty.
        return available.isEmpty() ? candidates : available;
    }

    @Override
    public void recordSuccess(Endpoint endpoint) {
        states.remove(keyOf(endpoint));
    }

    @Override
    public void recordFailure(Endpoint endpoint) {
        if (!config.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        states.compute(keyOf(endpoint), (key, state) -> {
            HealthState next = state == null ? new HealthState() : state;
            next.consecutiveFailures++;
            if (next.consecutiveFailures >= config.getFailureThreshold()
                    && next.unhealthySince == null) {
                next.unhealthySince = now;
            }
            return next;
        });
    }

    private boolean isAvailable(Endpoint endpoint, Instant now, Duration cooldown) {
        HealthState state = states.get(keyOf(endpoint));
        if (state == null || state.unhealthySince == null) {
            return true;
        }
        return now.isAfter(state.unhealthySince.plus(cooldown));
    }

    private String keyOf(Endpoint endpoint) {
        return endpoint.getProvider();
    }

    private static final class HealthState {
        int consecutiveFailures;
        Instant unhealthySince;
    }
}