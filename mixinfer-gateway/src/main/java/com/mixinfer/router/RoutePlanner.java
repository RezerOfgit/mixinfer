package com.mixinfer.router;

import com.mixinfer.router.health.EndpointHealthTracker;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds an ordered, health-filtered list of route targets for a model.
 *
 * <p>Encapsulates the routing preparation pipeline shared by the
 * non-streaming and streaming paths:
 * <pre>
 *   ModelRouter.route(model)
 *     → EndpointHealthTracker.filter(candidates)
 *     → EndpointSelector.order(healthy)
 * </pre>
 *
 * <p>The executor side is deliberately not abstracted: failover semantics
 * differ between streaming and non-streaming, and merging them would
 * introduce branching in the abstraction itself.
 */
@Component
public class RoutePlanner {

    private final ModelRouter router;
    private final EndpointHealthTracker healthTracker;
    private final EndpointSelector selector;

    public RoutePlanner(ModelRouter router,
                        EndpointHealthTracker healthTracker,
                        EndpointSelector selector) {
        this.router = router;
        this.healthTracker = healthTracker;
        this.selector = selector;
    }

    /**
     * Returns the ordered candidates to attempt for the given model.
     *
     * @throws com.mixinfer.exception.RoutingException if no route is
     *         configured for the model
     */
    public List<RouteTarget> plan(String model) {
        List<RouteTarget> candidates = router.route(model);
        List<RouteTarget> healthy = healthTracker.filter(candidates);
        return selector.order(healthy);
    }
}