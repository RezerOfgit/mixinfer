package com.mixinfer.service;

import com.mixinfer.config.MixInferProperties;
import com.mixinfer.router.*;
import com.mixinfer.router.health.EndpointHealthTracker;

import java.util.List;

/**
 * Shared fixtures for ChatCompletionService tests.
 * Kept as static methods rather than a base class so that tests
 * can compose only what they need.
 */
final class ChatCompletionServiceTestFixtures {

    private ChatCompletionServiceTestFixtures() {}

    /** Config with two providers ("primary", "backup") and one route with both targets. */
    static MixInferProperties twoTargetProperties() {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.ProviderConfig p1 = new MixInferProperties.ProviderConfig();
        p1.setName("primary");
        p1.setBaseUrl("http://primary");
        p1.setApiKey("k1");
        MixInferProperties.ProviderConfig p2 = new MixInferProperties.ProviderConfig();
        p2.setName("backup");
        p2.setBaseUrl("http://backup");
        p2.setApiKey("k2");
        properties.setProviders(List.of(p1, p2));

        MixInferProperties.TargetConfig t1 = new MixInferProperties.TargetConfig();
        t1.setProvider("primary");
        MixInferProperties.TargetConfig t2 = new MixInferProperties.TargetConfig();
        t2.setProvider("backup");
        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("gpt-4o-mini");
        route.setTargets(List.of(t1, t2));
        properties.setRoutes(List.of(route));

        return properties;
    }

    /** Single-provider config matching the streaming lifecycle test's setup. */
    static MixInferProperties singleTargetProperties(String providerName) {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.ProviderConfig p = new MixInferProperties.ProviderConfig();
        p.setName(providerName);
        p.setBaseUrl("http://unused");
        p.setApiKey("k");
        properties.setProviders(List.of(p));

        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("gpt-4o-mini");
        route.setProvider(providerName);
        properties.setRoutes(List.of(route));

        return properties;
    }

    static EndpointHealthTracker noopHealth() {
        return new EndpointHealthTracker() {
            @Override public List<RouteTarget> filter(List<RouteTarget> c) { return c; }
            @Override public void recordSuccess(Endpoint e) {}
            @Override public void recordFailure(Endpoint e) {}
        };
    }

    static RoutePlanner planner(MixInferProperties properties, EndpointHealthTracker health) {
        ModelRouter router = new ModelRouter(properties);
        EndpointSelector sequential = c -> c;
        return new RoutePlanner(router, health, sequential);
    }
}