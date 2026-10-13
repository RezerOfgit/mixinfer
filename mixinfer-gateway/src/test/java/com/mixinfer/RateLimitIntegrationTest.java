package com.mixinfer;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitIntegrationTest {

    static MockWebServer upstream;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) throws IOException {
        upstream = new MockWebServer();
        upstream.start();
        String base = upstream.url("/v1").toString().replaceAll("/$", "");

        registry.add("mixinfer.providers[0].name", () -> "openai-compatible");
        registry.add("mixinfer.providers[0].base-url", () -> base);
        registry.add("mixinfer.providers[0].api-key", () -> "k");
        registry.add("mixinfer.routes[0].model", () -> "gpt-4o-mini");
        registry.add("mixinfer.routes[0].targets[0].provider", () -> "openai-compatible");

        registry.add("mixinfer.virtual-keys[0].id", () -> "vk_rl");
        registry.add("mixinfer.virtual-keys[0].name", () -> "RL Test");
        registry.add("mixinfer.virtual-keys[0].key", () -> "sk-rl");
        registry.add("mixinfer.virtual-keys[0].rate-limit.requests-per-minute", () -> 2);

        registry.add("mixinfer.storage.sqlite.path",
                () -> System.getProperty("java.io.tmpdir")
                        + "/mixinfer-rl-test-" + UUID.randomUUID() + ".db");
    }

    @AfterAll
    static void stop() throws IOException {
        if (upstream != null) upstream.shutdown();
    }

    @Test
    void should_reject_after_exceeding_rate_limit_with_retry_after() {
        // Enqueue three responses; only first two are expected to consume.
        for (int i = 0; i < 2; i++) {
            upstream.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"id":"c","object":"chat.completion","created":0,
                             "model":"gpt-4o-mini","choices":[{"index":0,
                             "message":{"role":"assistant","content":"hi"},
                             "finish_reason":"stop"}]}
                            """));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("sk-rl");

        String body = """
                {"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}
                """;

        // First two: OK
        for (int i = 0; i < 2; i++) {
            ResponseEntity<String> r = restTemplate.postForEntity(
                    "http://localhost:" + port + "/v1/chat/completions",
                    new HttpEntity<>(body, headers), String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // Third: rejected
        ResponseEntity<String> r = restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(body, headers), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(r.getBody()).contains("rate_limited");
        assertThat(r.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(Long.parseLong(r.getHeaders().getFirst("Retry-After")))
                .isGreaterThan(0);
    }
}