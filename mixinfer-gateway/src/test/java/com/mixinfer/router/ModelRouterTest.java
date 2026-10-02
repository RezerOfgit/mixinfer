package com.mixinfer.router;

import com.mixinfer.config.MixInferProperties;
import com.mixinfer.exception.RoutingException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouterTest {

    @Test
    void should_route_known_model_to_endpoint() {
        MixInferProperties properties = buildProperties();

        ModelRouter router = new ModelRouter(properties);
        Endpoint endpoint = router.route("gpt-4o-mini");

        assertThat(endpoint.getProvider()).isEqualTo("openai-compatible");
        assertThat(endpoint.getBaseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(endpoint.getTimeout()).isNotNull();
    }

    @Test
    void should_throw_for_unknown_model() {
        ModelRouter router = new ModelRouter(buildProperties());

        assertThatThrownBy(() -> router.route("unknown-model"))
                .isInstanceOf(RoutingException.class)
                .hasMessageContaining("unknown-model");
    }

    @Test
    void should_fail_fast_when_route_references_unknown_provider() {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("gpt-4o-mini");
        route.setProvider("does-not-exist");
        properties.setRoutes(List.of(route));

        assertThatThrownBy(() -> new ModelRouter(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    private MixInferProperties buildProperties() {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.ProviderConfig provider = new MixInferProperties.ProviderConfig();
        provider.setName("openai-compatible");
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setApiKey("sk-test");
        properties.setProviders(List.of(provider));

        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("gpt-4o-mini");
        route.setProvider("openai-compatible");
        properties.setRoutes(List.of(route));

        return properties;
    }
}