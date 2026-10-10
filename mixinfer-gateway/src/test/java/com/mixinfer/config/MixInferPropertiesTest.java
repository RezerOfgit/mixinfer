package com.mixinfer.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext
class MixInferPropertiesTest {

    @Autowired
    private MixInferProperties properties;

    @Test
    void should_load_config_from_application_yml() {
        assertThat(properties.getApiKeys()).isNotEmpty();
        assertThat(properties.getProviders()).isNotEmpty();
        assertThat(properties.getRoutes()).isNotEmpty();
        assertThat(properties.getApiKeys().get(0).getKey()).isEqualTo("sk-mixinfer-dev");
    }

    @Test
    void should_resolve_single_provider_form_to_one_target() {
        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("m");
        route.setProvider("p1");

        List<MixInferProperties.TargetConfig> targets = route.resolveTargets();

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).getProvider()).isEqualTo("p1");
    }

    @Test
    void should_resolve_multi_target_form_in_declaration_order() {
        MixInferProperties.TargetConfig t1 = new MixInferProperties.TargetConfig();
        t1.setProvider("primary");
        MixInferProperties.TargetConfig t2 = new MixInferProperties.TargetConfig();
        t2.setProvider("backup");

        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("m");
        route.setTargets(List.of(t1, t2));

        List<MixInferProperties.TargetConfig> targets = route.resolveTargets();

        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).getProvider()).isEqualTo("primary");
        assertThat(targets.get(1).getProvider()).isEqualTo("backup");
    }

    @Test
    void should_prefer_targets_over_legacy_provider() {
        MixInferProperties.TargetConfig t = new MixInferProperties.TargetConfig();
        t.setProvider("new");

        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("m");
        route.setProvider("legacy");
        route.setTargets(List.of(t));

        List<MixInferProperties.TargetConfig> targets = route.resolveTargets();

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).getProvider()).isEqualTo("new");
    }

    @Test
    void should_return_empty_targets_when_nothing_configured() {
        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("m");

        assertThat(route.resolveTargets()).isEmpty();
    }
}
