package com.mixinfer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds the {@code mixinfer.*} section of application.yml.
 */
@Data
@Component
@ConfigurationProperties(prefix = "mixinfer")
public class MixInferProperties {

    /** Client-facing API keys used to authenticate requests to MixInfer. */
    private List<ApiKeyConfig> apiKeys = new ArrayList<>();

    /** Upstream LLM providers available for routing. */
    private List<ProviderConfig> providers = new ArrayList<>();

    /** Mapping from logical model name to a provider. */
    private List<RouteConfig> routes = new ArrayList<>();

    /** Health tracking configuration for multi-endpoint routing. */
    private HealthConfig health = new HealthConfig();

    /**
     * A single client-facing API key.
     */
    @Data
    public static class ApiKeyConfig {
        private String key;
        private String name;
    }

    /**
     * Configuration for an upstream LLM provider.
     */
    @Data
    public static class ProviderConfig {
        private String name;
        private String baseUrl;
        private String apiKey;
        private List<String> models = new ArrayList<>();
    }

    /**
     * Mapping from a logical model name to one or more upstream targets.
     */
    @Data
    public static class RouteConfig {

        private String model;

        /**
         * V0.3+ multi-target form. Declaration order serves as implicit
         * priority: targets are tried in the order listed. When present,
         * {@link #provider} is ignored.
         */
        private List<TargetConfig> targets = new ArrayList<>();

        /**
         * V0.1-V0.2 single-provider form. Kept for backward compatibility.
         * New configurations should use {@link #targets}.
         */
        private String provider;

        /**
         * Returns the effective target list, resolving the legacy
         * single-provider form into a one-element list.
         */
        public List<TargetConfig> resolveTargets() {
            if (!targets.isEmpty()) {
                return targets;
            }
            if (provider != null && !provider.isBlank()) {
                TargetConfig single = new TargetConfig();
                single.setProvider(provider);
                return List.of(single);
            }
            return List.of();
        }
    }

    /**
     * A single upstream target within a route.
     *
     * <p>V0.3.0 carries only the provider name. Future versions may add
     * weight, priority, or tags without changing the surrounding config shape.
     */
    @Data
    public static class TargetConfig {
        private String provider;
    }

    /**
     * Configuration for endpoint health tracking.
     *
     * <p>Health tracking is advisory: it reduces the probability of hitting
     * a known-bad endpoint but never makes routing decisions on its own.
     */
    @Data
    public static class HealthConfig {

        /** Whether health tracking is enabled. */
        private boolean enabled = true;

        /** Consecutive failures before an endpoint is marked unhealthy. */
        private int failureThreshold = 3;

        /** Seconds an unhealthy endpoint stays excluded before retry. */
        private int cooldownSeconds = 30;
    }
}
