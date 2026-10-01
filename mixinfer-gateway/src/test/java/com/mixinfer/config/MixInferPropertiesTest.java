package com.mixinfer.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
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
}
