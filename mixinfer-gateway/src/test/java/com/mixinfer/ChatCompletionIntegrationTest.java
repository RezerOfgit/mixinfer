package com.mixinfer;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test: client -> MixInfer -> MockWebServer -> MixInfer -> client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatCompletionIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    static MockWebServer mockUpstream;

    @BeforeAll
    static void startMock() throws IOException {
        mockUpstream = new MockWebServer();
        mockUpstream.start();
    }

    @AfterAll
    static void stopMock() throws IOException {
        mockUpstream.shutdown();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("mixinfer.providers[0].base-url",
                () -> mockUpstream.url("/v1").toString().replaceAll("/$", ""));
    }

    @Test
    void should_return_response_from_upstream() throws Exception {
        mockUpstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": "chatcmpl-test",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o-mini",
                          "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": "hello from mock"},
                            "finish_reason": "stop"
                          }],
                          "usage": {
                            "prompt_tokens": 5,
                            "completion_tokens": 3,
                            "total_tokens": 8
                          }
                        }
                        """));

        String body = """
                {
                  "model": "gpt-4o-mini",
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("sk-mixinfer-dev");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"content\":\"hello from mock\"")
                .contains("\"total_tokens\":8");

        // 验证 MixInfer 真的往上游发了一次请求
        assertThat(mockUpstream.takeRequest().getPath()).contains("/chat/completions");

        RecordedRequest recorded = mockUpstream.takeRequest();
        assertThat(recorded.getPath()).contains("/chat/completions");
    }

    @Test
    void should_reject_missing_api_key() {
        String body = """
                {"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]}
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // no Authorization header

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("invalid_api_key");
    }

    @Test
    void should_return_404_for_unknown_model() {
        String body = """
                {"model": "unknown-model", "messages": [{"role": "user", "content": "hi"}]}
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("sk-mixinfer-dev");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("model_not_found");
    }
}