package com.mixinfer.router;

import com.mixinfer.config.MixInferProperties;
import com.mixinfer.exception.RoutingException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves a logical model name to a concrete {@link Endpoint}.
 * Routing rules are built once at startup from {@link MixInferProperties}.
 */
@Component
public class ModelRouter {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Map<String, Endpoint> routes;

    public ModelRouter(MixInferProperties properties) {
        this.routes = buildRoutes(properties);
    }

    /**
     * Resolve the endpoint for the given logical model name.
     *
     * @throws RoutingException if no route is configured for this model
     */
    public Endpoint route(String model) {
        Endpoint endpoint = routes.get(model);
        if (endpoint == null) {
            throw new RoutingException("No route configured for model: " + model);
        }
        return endpoint;
    }

    private Map<String, Endpoint> buildRoutes(MixInferProperties properties) {
        Map<String, MixInferProperties.ProviderConfig> providersByName =
                properties.getProviders().stream()
                        .collect(Collectors.toMap(
                                MixInferProperties.ProviderConfig::getName,
                                p -> p));

        Map<String, Endpoint> result = new HashMap<>();
        for (MixInferProperties.RouteConfig route : properties.getRoutes()) {
            MixInferProperties.ProviderConfig provider = providersByName.get(route.getProvider());
            if (provider == null) {
                throw new IllegalStateException(
                        "Route references unknown provider: " + route.getProvider());
            }
            result.put(route.getModel(), Endpoint.builder()
                    .provider(provider.getName())
                    .baseUrl(provider.getBaseUrl())
                    .apiKey(provider.getApiKey())
                    .timeout(DEFAULT_TIMEOUT)
                    .build());
        }
        return Map.copyOf(result);
    }
}
