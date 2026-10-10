package com.mixinfer;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end failover tests with two independent upstream servers.
 *
 * <p>Uses two MockWebServer instances (primary and backup) instead of
 * the shared upstream used in ChatCompletionIntegrationTest, because
 * failover needs to distinguish which server was hit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class MultiEndpointFailoverIntegrationTest {

    static MockWebServer primaryUpstream;
    static MockWebServer backupUpstream;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) throws IOException {
        primaryUpstream = new MockWebServer();
        primaryUpstream.start();
        backupUpstream = new MockWebServer();
        backupUpstream.start();

        String primaryUrl = primaryUpstream.url("/v1").toString().replaceAll("/$", "");
        String backupUrl = backupUpstream.url("/v1").toString().replaceAll("/$", "");

        registry.add("mixinfer.providers[0].name", () -> "primary");
        registry.add("mixinfer.providers[0].base-url", () -> primaryUrl);
        registry.add("mixinfer.providers[0].api-key", () -> "k1");

        registry.add("mixinfer.providers[1].name", () -> "backup");
        registry.add("mixinfer.providers[1].base-url", () -> backupUrl);
        registry.add("mixinfer.providers[1].api-key", () -> "k2");

        registry.add("mixinfer.routes[0].model", () -> "gpt-4o-mini");
        registry.add("mixinfer.routes[0].targets[0].provider", () -> "primary");
        registry.add("mixinfer.routes[0].targets[1].provider", () -> "backup");

        registry.add("mixinfer.usage.file",
                () -> System.getProperty("java.io.tmpdir") + "/mixinfer-failover-usage.log");
    }

    @AfterAll
    static void stopMocks() throws IOException {
        if (primaryUpstream != null) {
            primaryUpstream.shutdown();
        }
        if (backupUpstream != null) {
            backupUpstream.shutdown();
        }
    }

    @Test
    void should_failover_to_backup_on_primary_5xx() throws Exception {
        primaryUpstream.enqueue(new MockResponse().setResponseCode(500));
        backupUpstream.enqueue(successResponse("from backup"));

        ResponseEntity<String> response = postJson(nonStreamingBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("from backup");

        // Primary was hit once, then backup.
        assertThat(primaryUpstream.takeRequest().getPath()).contains("/chat/completions");
        assertThat(backupUpstream.takeRequest().getPath()).contains("/chat/completions");
    }

    @Test
    void should_not_failover_on_primary_401() throws Exception {
        primaryUpstream.enqueue(new MockResponse().setResponseCode(401));
        // No response enqueued on backup: if it gets hit, the test will hang.

        ResponseEntity<String> response = postJson(nonStreamingBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).contains("upstream_error");
    }

    @Test
    void should_return_502_when_all_endpoints_fail() throws Exception {
        primaryUpstream.enqueue(new MockResponse().setResponseCode(500));
        backupUpstream.enqueue(new MockResponse().setResponseCode(503));

        ResponseEntity<String> response = postJson(nonStreamingBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).contains("All");
    }

    @Test
    void should_failover_streaming_open_on_primary_5xx() throws Exception {
        primaryUpstream.enqueue(new MockResponse().setResponseCode(500));
        backupUpstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"id":"c1","model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"hello"}}]}

                        data: [DONE]

                        """));

        String body = """
                {"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"hi"}]}
                """;

        ResponseEntity<String> response = postJson(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"content\":\"hello\"");
        assertThat(response.getBody()).contains("[DONE]");
    }

    private ResponseEntity<String> postJson(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("sk-mixinfer-dev");
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(body, headers),
                String.class);
    }

    private MockResponse successResponse(String content) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"c1","object":"chat.completion","created":1700000000,
                         "model":"gpt-4o-mini","choices":[{"index":0,
                         "message":{"role":"assistant","content":"%s"},
                         "finish_reason":"stop"}]}
                        """.formatted(content));
    }

    private String nonStreamingBody() {
        return """
                {"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}
                """;
    }
}