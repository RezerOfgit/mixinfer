package com.mixinfer.router;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SequentialSelectorTest {

    private final SequentialSelector selector = new SequentialSelector();

    @Test
    void should_preserve_order() {
        List<RouteTarget> input = List.of(
                target("a"),
                target("b"),
                target("c"));

        assertThat(selector.order(input)).isSameAs(input);
    }

    @Test
    void should_handle_empty_list() {
        assertThat(selector.order(List.of())).isEmpty();
    }

    private RouteTarget target(String provider) {
        return RouteTarget.builder()
                .endpoint(Endpoint.builder()
                        .provider(provider)
                        .baseUrl("http://" + provider)
                        .apiKey("k")
                        .connectTimeout(Duration.ofSeconds(5))
                        .requestTimeout(Duration.ofSeconds(30))
                        .build())
                .build();
    }
}