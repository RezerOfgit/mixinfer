package com.mixinfer.router;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Returns candidates unchanged, preserving declaration order.
 * This is the V0.3.0 default: declaration order is priority order.
 */
@Component
public class SequentialSelector implements EndpointSelector {

    @Override
    public List<RouteTarget> order(List<RouteTarget> candidates) {
        return candidates;
    }
}