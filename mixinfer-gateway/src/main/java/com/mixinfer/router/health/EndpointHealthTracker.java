package com.mixinfer.router.health;

import com.mixinfer.router.Endpoint;
import com.mixinfer.router.RouteTarget;

import java.util.List;

/**
 * Tracks endpoint health across requests.
 *
 * <p>Health tracking is advisory, not correctness-critical: {@link #filter}
 * must never return an empty list when the input is non-empty. If all
 * endpoints are marked unhealthy, all of them are returned so that the
 * gateway degrades to "try everything" instead of "fail immediately".
 */
public interface EndpointHealthTracker {

    /**
     * Filters out currently-unhealthy endpoints.
     * Returns the input list unchanged when all endpoints are unhealthy
     * or health tracking is disabled.
     */
    List<RouteTarget> filter(List<RouteTarget> candidates);

    /** Records a successful call against the endpoint. */
    void recordSuccess(Endpoint endpoint);

    /** Records a failed call against the endpoint. */
    void recordFailure(Endpoint endpoint);
}