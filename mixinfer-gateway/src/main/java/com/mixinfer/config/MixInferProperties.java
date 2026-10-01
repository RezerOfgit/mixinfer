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
     * Mapping from a logical model name to a provider.
     */
    @Data
    public static class RouteConfig {
        private String model;
        private String provider;
    }
}
