package com.mixinfer.router;

import java.util.List;

/**
 * Orders a list of route targets before failover execution.
 *
 * <p>V0.3 ships only {@link SequentialSelector}; the interface exists so
 * that future strategies (weighted random, cost-aware, latency-aware) can
 * be added without changing the Router or the executor.
 */
public interface EndpointSelector {

    /**
     * Returns the candidates in the order they should be attempted.
     * The returned list must contain exactly the same elements as the input.
     */
    List<RouteTarget> order(List<RouteTarget> candidates);
}