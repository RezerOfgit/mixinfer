package com.mixinfer.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.converter.LlmToOpenAIConverter;
import com.mixinfer.converter.OpenAIStreamConverter;
import com.mixinfer.domain.ContentPart;
import com.mixinfer.domain.LlmMessage;
import com.mixinfer.domain.LlmRequest;
import com.mixinfer.domain.LlmStreamChunk;
import com.mixinfer.router.Endpoint;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAICompatibleStreamingProviderTest {

    private MockWebServer server;
    private OpenAICompatibleStreamingProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new OpenAICompatibleStreamingProvider(
                new ObjectMapper(),
                new LlmToOpenAIConverter(),
                new OpenAIStreamConverter());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void should_stream_chunks_in_order() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"id":"c1","model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant"}}]}

                        data: {"id":"c1","model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"Hello"}}]}

                        data: {"id":"c1","model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":" world"}}]}

                        data: {"id":"c1","model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """));

        try (LlmStream stream = provider.invokeStream(buildRequest(), buildEndpoint())) {
            assertThat(stream.next().getDelta().getRole()).isEqualTo("assistant");
            assertThat(text(stream.next())).isEqualTo("Hello");
            assertThat(text(stream.next())).isEqualTo(" world");
            assertThat(stream.next().getFinishReason()).isEqualTo("stop");
            assertThat(stream.next()).isNull();
        }
    }

    @Test
    void should_return_null_when_stream_closes_without_done_marker() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}\n\n"));

        try (LlmStream stream = provider.invokeStream(buildRequest(), buildEndpoint())) {
            assertThat(text(stream.next())).isEqualTo("x");
            assertThat(stream.next()).isNull();
        }
    }

    @Test
    void should_throw_on_non_2xx_status() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("error"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> provider.invokeStream(buildRequest(), buildEndpoint()))
                .hasMessageContaining("500");
    }

    private String text(LlmStreamChunk chunk) {
        return ((ContentPart.TextPart) chunk.getDelta().getContent().get(0)).text();
    }

    private LlmRequest buildRequest() {
        return LlmRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(LlmMessage.builder()
                        .role("user")
                        .content(List.of(new ContentPart.TextPart("hi")))
                        .build()))
                .stream(true)
                .build();
    }

    private Endpoint buildEndpoint() {
        return Endpoint.builder()
                .provider("openai-compatible")
                .baseUrl(server.url("/v1").toString().replaceAll("/$", ""))
                .apiKey("test-key")
                .timeout(Duration.ofSeconds(5))
                .build();
    }
}