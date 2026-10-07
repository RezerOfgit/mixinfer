package com.mixinfer.router;

import lombok.Builder;
import lombok.Value;

/**
 * A single routing entry for a logical model.
 *
 * <p>In V0.3.0 a RouteTarget holds only the resolved Endpoint; the
 * declaration order of multiple targets carries the priority semantics.
 *
 * <p>RouteTarget exists as a separate abstraction from Endpoint so that
 * future routing metadata (weight, priority, tags, conditional matching)
 * can be added without changing Endpoint or the Router's contract.
 */
@Value
@Builder
public class RouteTarget {
    Endpoint endpoint;
}