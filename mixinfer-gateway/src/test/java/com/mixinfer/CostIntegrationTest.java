package com.mixinfer;

import com.mixinfer.domain.UsageRecord;
import com.mixinfer.metering.UsageRepository;
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

/**
 * Verifies cost flows from configuration through the request path
 * into the persisted usage record.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CostIntegrationTest {

    static MockWebServer upstream;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    UsageRepository usageRepository;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) throws IOException {
        upstream = new MockWebServer();
        upstream.start();
        String base = upstream.url("/v1").toString().replaceAll("/$", "");

        registry.add("mixinfer.providers[0].name", () -> "openai-compatible");
        registry.add("mixinfer.providers[0].base-url", () -> base);
        registry.add("mixinfer.providers[0].api-key", () -> "k");

        // Pricing for gpt-4o-mini: input 100,000 microcents/1M,
        // output 200,000 microcents/1M, version "test-v1".
        registry.add("mixinfer.providers[0].pricing[0].upstream-model", () -> "gpt-4o-mini");
        registry.add("mixinfer.providers[0].pricing[0].version", () -> "test-v1");
        registry.add("mixinfer.providers[0].pricing[0].input-price-per-million-microcents",
                () -> 100_000L);
        registry.add("mixinfer.providers[0].pricing[0].output-price-per-million-microcents",
                () -> 200_000L);

        registry.add("mixinfer.routes[0].model", () -> "gpt-4o-mini");
        registry.add("mixinfer.routes[0].targets[0].provider", () -> "openai-compatible");

        registry.add("mixinfer.storage.sqlite.path",
                () -> System.getProperty("java.io.tmpdir")
                        + "/mixinfer-cost-test-" + UUID.randomUUID() + ".db");
    }

    @AfterAll
    static void stop() throws IOException {
        if (upstream != null) upstream.shutdown();
    }

    @Test
    void should_persist_cost_with_pricing_version() {
        // 1000 input tokens at 100,000 microcents/1M -> 100 microcents
        // 500 output tokens at 200,000 microcents/1M -> 100 microcents
        // Total: 200 microcents
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"c1","object":"chat.completion","created":0,
                         "model":"gpt-4o-mini","choices":[{"index":0,
                         "message":{"role":"assistant","content":"hi"},
                         "finish_reason":"stop"}],
                         "usage":{"prompt_tokens":1000,
                                  "completion_tokens":500,
                                  "total_tokens":1500}}
                        """));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("sk-mixinfer-dev");

        String body = """
                {"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String requestId = response.getHeaders().getFirst("X-MixInfer-Request-Id");
        assertThat(requestId).isNotNull();

        UsageRecord record = usageRepository.findByRequestId(requestId).orElseThrow();
        assertThat(record.getCostMicrocents()).isEqualTo(200L);
        assertThat(record.getPricingVersion()).isEqualTo("test-v1");
        assertThat(record.getUpstreamModel()).isEqualTo("gpt-4o-mini");
        assertThat(record.getEndpointId()).isEqualTo("openai-compatible");
        assertThat(record.isSuccess()).isTrue();
        assertThat(record.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void should_persist_null_cost_when_pricing_missing() {
        // Enqueue a response with a model that has no pricing entry.
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"c2","object":"chat.completion","created":0,
                         "model":"unknown-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"hi"},
                         "finish_reason":"stop"}],
                         "usage":{"prompt_tokens":100,
                                  "completion_tokens":50,
                                  "total_tokens":150}}
                        """));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("sk-mixinfer-dev");

        String body = """
                {"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String requestId = response.getHeaders().getFirst("X-MixInfer-Request-Id");
        UsageRecord record = usageRepository.findByRequestId(requestId).orElseThrow();

        // Upstream reported "unknown-model"; no pricing for it -> null cost.
        assertThat(record.getUpstreamModel()).isEqualTo("unknown-model");
        assertThat(record.getCostMicrocents()).isNull();
        assertThat(record.getPricingVersion()).isNull();
        assertThat(record.isSuccess()).isTrue();
    }
}