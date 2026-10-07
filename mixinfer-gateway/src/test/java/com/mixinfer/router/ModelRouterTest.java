package com.mixinfer.router;

import com.mixinfer.config.MixInferProperties;
import com.mixinfer.exception.RoutingException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouterTest {

    @Test
    void should_route_known_model_to_ordered_targets() {
        ModelRouter router = new ModelRouter(buildProperties());

        List<RouteTarget> targets = router.route("gpt-4o-mini");

        assertThat(targets).hasSize(1);
        Endpoint endpoint = targets.get(0).getEndpoint();
        assertThat(endpoint.getProvider()).isEqualTo("openai-compatible");
        assertThat(endpoint.getBaseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(endpoint.getTimeout()).isNotNull();
    }

    @Test
    void should_route_to_multiple_targets_in_declaration_order() {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.ProviderConfig primary = new MixInferProperties.ProviderConfig();
        primary.setName("primary");
        primary.setBaseUrl("https://primary.example.com");
        primary.setApiKey("k1");

        MixInferProperties.ProviderConfig backup = new MixInferProperties.ProviderConfig();
        backup.setName("backup");
        backup.setBaseUrl("https://backup.example.com");
        backup.setApiKey("k2");

        properties.setProviders(List.of(primary, backup));

        MixInferProperties.TargetConfig t1 = new MixInferProperties.TargetConfig();
        t1.setProvider("primary");
        MixInferProperties.TargetConfig t2 = new MixInferProperties.TargetConfig();
        t2.setProvider("backup");

        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("gpt-4o-mini");
        route.setTargets(List.of(t1, t2));
        properties.setRoutes(List.of(route));

        ModelRouter router = new ModelRouter(properties);
        List<RouteTarget> targets = router.route("gpt-4o-mini");

        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).getEndpoint().getProvider()).isEqualTo("primary");
        assertThat(targets.get(1).getEndpoint().getProvider()).isEqualTo("backup");
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

        MixInferProperties.TargetConfig target = new MixInferProperties.TargetConfig();
        target.setProvider("does-not-exist");

        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("gpt-4o-mini");
        route.setTargets(List.of(target));
        properties.setRoutes(List.of(route));

        assertThatThrownBy(() -> new ModelRouter(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void should_fail_fast_when_route_has_no_targets() {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("gpt-4o-mini");
        properties.setRoutes(List.of(route));

        assertThatThrownBy(() -> new ModelRouter(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no targets");
    }

    private MixInferProperties buildProperties() {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.ProviderConfig provider = new MixInferProperties.ProviderConfig();
        provider.setName("openai-compatible");
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setApiKey("sk-test");
        properties.setProviders(List.of(provider));

        MixInferProperties.TargetConfig target = new MixInferProperties.TargetConfig();
        target.setProvider("openai-compatible");

        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("gpt-4o-mini");
        route.setTargets(List.of(target));
        properties.setRoutes(List.of(route));

        return properties;
    }
}