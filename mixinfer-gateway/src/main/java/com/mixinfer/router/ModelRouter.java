package com.mixinfer.router;

import com.mixinfer.config.MixInferProperties;
import com.mixinfer.exception.RoutingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves a logical model name to an ordered list of {@link RouteTarget}.
 * The declaration order of targets in configuration serves as implicit
 * priority: the first target is attempted first.
 */
@Component
@Slf4j
public class ModelRouter {

//    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Map<String, List<RouteTarget>> routes;

    public ModelRouter(MixInferProperties properties) {
        this.routes = buildRoutes(properties);
    }

    /**
     * Resolve the ordered list of route targets for the given logical model.
     *
     * @throws RoutingException if no route is configured for this model
     */
    public List<RouteTarget> route(String model) {
        List<RouteTarget> targets = routes.get(model);
        if (targets == null || targets.isEmpty()) {
            throw new RoutingException("No route configured for model: " + model);
        }
        return targets;
    }

    private Map<String, List<RouteTarget>> buildRoutes(MixInferProperties properties) {
        Map<String, MixInferProperties.ProviderConfig> providersByName =
                properties.getProviders().stream()
                        .collect(Collectors.toMap(
                                MixInferProperties.ProviderConfig::getName,
                                p -> p));

        Map<String, List<RouteTarget>> result = new HashMap<>();
        for (MixInferProperties.RouteConfig route : properties.getRoutes()) {
            List<MixInferProperties.TargetConfig> targets = route.resolveTargets();
            if (targets.isEmpty()) {
                throw new IllegalStateException(
                        "Route '" + route.getModel() + "' has no targets configured");
            }

            List<RouteTarget> resolved = targets.stream()
                    .map(t -> resolveTarget(t, providersByName, route.getModel()))
                    .toList();

            result.put(route.getModel(), resolved);
        }
        return Map.copyOf(result);
    }

    private RouteTarget resolveTarget(
            MixInferProperties.TargetConfig target,
            Map<String, MixInferProperties.ProviderConfig> providersByName,
            String model) {

        MixInferProperties.ProviderConfig provider = providersByName.get(target.getProvider());
        if (provider == null) {
            throw new IllegalStateException(
                    "Route '" + model + "' references unknown provider: " + target.getProvider());
        }

        Map<String, Pricing> pricing = provider.getPricing().stream()
                .collect(Collectors.toUnmodifiableMap(
                        MixInferProperties.PricingConfig::getUpstreamModel,
                        p -> {
                            if (p.getVersion() == null || p.getVersion().isBlank()) {
                                throw new IllegalStateException(
                                        "Pricing entry for model '" + p.getUpstreamModel()
                                                + "' under provider '" + provider.getName()
                                                + "' is missing a version. Set 'version' to a "
                                                + "stable, reproducible identifier.");
                            }
                            logPricingFingerprint(provider.getName(), p);
                            return new Pricing(
                                    p.getInputPricePerMillionMicrocents(),
                                    p.getOutputPricePerMillionMicrocents(),
                                    p.getVersion());
                        },
                        (a, b) -> a));

        Endpoint endpoint = Endpoint.builder()
                .id(provider.getName())
                .provider(provider.getName())
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .connectTimeout(Duration.ofSeconds(provider.getConnectTimeoutSeconds()))
                .requestTimeout(Duration.ofSeconds(provider.getRequestTimeoutSeconds()))
                .pricing(pricing)
                .build();

        return RouteTarget.builder().endpoint(endpoint).build();
    }

    /**
     * Logs a fingerprint of the pricing entry so that a version string
     * pointing to different prices is detectable after the fact.
     */
    private void logPricingFingerprint(String providerName,
                                       MixInferProperties.PricingConfig p) {
        String fingerprint = Long.toHexString(
                (p.getInputPricePerMillionMicrocents() * 31)
                        + p.getOutputPricePerMillionMicrocents());
        log.info("Pricing loaded provider={} model={} version={} fingerprint={}",
                providerName, p.getUpstreamModel(), p.getVersion(), fingerprint);
    }
}