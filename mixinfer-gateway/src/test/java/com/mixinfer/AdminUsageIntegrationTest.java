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
import org.springframework.http.HttpMethod;
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
class AdminUsageIntegrationTest {

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
        registry.add("mixinfer.admin.api-key", () -> "sk-admin-test");
        registry.add("mixinfer.storage.sqlite.path",
                () -> System.getProperty("java.io.tmpdir")
                        + "/mixinfer-admin-test-" + UUID.randomUUID() + ".db");
    }

    @AfterAll
    static void stop() throws IOException {
        if (upstream != null) upstream.shutdown();
    }

    @Test
    void should_reject_admin_query_with_client_key() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("sk-mixinfer-dev");    // client key

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/admin/usage/records?limit=10",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("invalid_admin_key");
    }

    @Test
    void should_reject_admin_query_with_no_key() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/admin/usage/records?limit=10",
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void should_allow_admin_query_with_admin_key() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("sk-admin-test");

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/admin/usage/records?limit=10",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void should_return_persisted_record_via_admin_query() {
        // Fire a normal chat request first.
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"c1","object":"chat.completion","created":1700000000,
                         "model":"gpt-4o-mini","choices":[{"index":0,
                         "message":{"role":"assistant","content":"hi"},
                         "finish_reason":"stop"}],
                         "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
                        """));

        HttpHeaders chatHeaders = new HttpHeaders();
        chatHeaders.setContentType(MediaType.APPLICATION_JSON);
        chatHeaders.setBearerAuth("sk-mixinfer-dev");

        String chatBody = """
                {"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}
                """;

        restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(chatBody, chatHeaders),
                String.class);

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth("sk-admin-test");

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/admin/usage/records"
                        + "?model=gpt-4o-mini&limit=10",
                HttpMethod.GET, new HttpEntity<>(adminHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"model\":\"gpt-4o-mini\"")
                .contains("\"virtualKeyId\":\"vk_default\"")
                .doesNotContain("sk-mixinfer-dev")
                .doesNotContain("sk-admin-test");
    }

    @Test
    void should_reject_invalid_group_by() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("sk-admin-test");

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/admin/usage/aggregate"
                        + "?from=2026-01-01T00:00:00Z"
                        + "&to=2026-12-31T23:59:59Z"
                        + "&groupBy=whatever",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}