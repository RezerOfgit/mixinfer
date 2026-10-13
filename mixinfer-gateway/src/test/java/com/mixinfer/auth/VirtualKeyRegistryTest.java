package com.mixinfer.auth;

import com.mixinfer.config.MixInferProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualKeyRegistryTest {

    @Test
    void should_resolve_new_format_virtual_key() {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.VirtualKeyConfig cfg = new MixInferProperties.VirtualKeyConfig();
        cfg.setId("vk_team_a");
        cfg.setName("Team A");
        cfg.setKey("sk-a");
        MixInferProperties.RateLimitPolicyConfig rl = new MixInferProperties.RateLimitPolicyConfig();
        rl.setRequestsPerMinute(60);
        cfg.setRateLimit(rl);
        properties.setVirtualKeys(List.of(cfg));

        VirtualKeyRegistry registry = new VirtualKeyRegistry(properties);

        VirtualKey vk = registry.findBySecret("sk-a").orElseThrow();
        assertThat(vk.getId()).isEqualTo("vk_team_a");
        assertThat(vk.getName()).isEqualTo("Team A");
        assertThat(vk.getRateLimit().limit()).isEqualTo(60);
    }

    @Test
    void should_upgrade_legacy_api_key_with_derived_id() {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.ApiKeyConfig cfg = new MixInferProperties.ApiKeyConfig();
        cfg.setName("Team A");
        cfg.setKey("sk-legacy");
        properties.setApiKeys(List.of(cfg));

        VirtualKeyRegistry registry = new VirtualKeyRegistry(properties);

        VirtualKey vk = registry.findBySecret("sk-legacy").orElseThrow();
        assertThat(vk.getId()).startsWith("legacy-");
        assertThat(vk.getName()).isEqualTo("Team A");
        assertThat(vk.getRateLimit()).isNull();
    }

    @Test
    void should_produce_deterministic_legacy_id() {
        MixInferProperties p1 = new MixInferProperties();
        MixInferProperties.ApiKeyConfig c1 = new MixInferProperties.ApiKeyConfig();
        c1.setName("  Team A  ");
        c1.setKey("sk-1");
        p1.setApiKeys(List.of(c1));

        MixInferProperties p2 = new MixInferProperties();
        MixInferProperties.ApiKeyConfig c2 = new MixInferProperties.ApiKeyConfig();
        c2.setName("team a");
        c2.setKey("sk-2");
        p2.setApiKeys(List.of(c2));

        String id1 = new VirtualKeyRegistry(p1).findBySecret("sk-1").orElseThrow().getId();
        String id2 = new VirtualKeyRegistry(p2).findBySecret("sk-2").orElseThrow().getId();

        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void should_reject_new_format_missing_id() {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.VirtualKeyConfig cfg = new MixInferProperties.VirtualKeyConfig();
        cfg.setName("Team A");
        cfg.setKey("sk-a");
        properties.setVirtualKeys(List.of(cfg));

        assertThatThrownBy(() -> new VirtualKeyRegistry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing required 'id'");
    }

    @Test
    void should_reject_duplicate_secret() {
        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.ApiKeyConfig legacy = new MixInferProperties.ApiKeyConfig();
        legacy.setName("Team A");
        legacy.setKey("sk-shared");
        properties.setApiKeys(List.of(legacy));

        MixInferProperties.VirtualKeyConfig vk = new MixInferProperties.VirtualKeyConfig();
        vk.setId("vk_b");
        vk.setName("Team B");
        vk.setKey("sk-shared");
        properties.setVirtualKeys(List.of(vk));

        assertThatThrownBy(() -> new VirtualKeyRegistry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void should_return_empty_for_unknown_secret() {
        VirtualKeyRegistry registry = new VirtualKeyRegistry(new MixInferProperties());

        assertThat(registry.findBySecret("nope")).isEmpty();
        assertThat(registry.findBySecret(null)).isEmpty();
        assertThat(registry.findBySecret("")).isEmpty();
    }
}