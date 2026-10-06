package com.mixinfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.converter.LlmToOpenAIConverter;
import com.mixinfer.converter.LlmToOpenAIStreamConverter;
import com.mixinfer.converter.OpenAIToLlmConverter;
import com.mixinfer.domain.ContentPart;
import com.mixinfer.domain.LlmRequest;
import com.mixinfer.domain.LlmStreamChunk;
import com.mixinfer.domain.UsageRecord;
import com.mixinfer.metering.UsageRecorder;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIMessage;
import com.mixinfer.provider.LlmStream;
import com.mixinfer.provider.ProviderRegistry;
import com.mixinfer.provider.StreamingLlmProvider;
import com.mixinfer.router.Endpoint;
import com.mixinfer.router.ModelRouter;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Verifies the lifecycle behavior of ChatCompletionService.handleStream
 * without starting Spring: client disconnect MUST close the upstream stream.
 */
class ChatCompletionServiceStreamingLifecycleTest {

    @Test
    void should_close_upstream_stream_when_client_disconnects() throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);

        LlmStream fakeStream = new LlmStream() {
            private int yielded = 0;

            @Override
            public LlmStreamChunk next() {
                return LlmStreamChunk.builder()
                        .id("c1")
                        .model("gpt-4o-mini")
                        .delta(com.mixinfer.domain.LlmMessageDelta.builder()
                                .content(List.of(new ContentPart.TextPart("chunk-" + (yielded++))))
                                .build())
                        .build();
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        StreamingLlmProvider fakeProvider = new StreamingLlmProvider() {
            @Override
            public String name() {
                return "openai-compatible";
            }

            @Override
            public LlmStream invokeStream(LlmRequest request, Endpoint endpoint) {
                return fakeStream;
            }
        };

        ChatCompletionService service = buildServiceWithProvider(fakeProvider);

        // Mock ServletOutputStream so flush() throws (client disconnected).
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream out =
                Mockito.mock(jakarta.servlet.ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(out);
        doThrow(new IOException("client disconnected"))
                .when(out).flush();

        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setModel("gpt-4o-mini");
        request.setStream(true);
        request.setMessages(List.of(new OpenAIMessage("user", "hi")));

        assertThatThrownBy(() -> service.handleStream(request, response))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("client disconnected");

        assertThat(closed).as("upstream stream must be closed").isTrue();
    }

    private ChatCompletionService buildServiceWithProvider(StreamingLlmProvider provider) {
        ObjectMapper objectMapper = new ObjectMapper();

        // Build a Registry with only the streaming side populated.
        ProviderRegistry registry = new ProviderRegistry(
                List.of(),
                List.of(provider));

        // Build a router that maps "gpt-4o-mini" to an endpoint named after the provider.
        com.mixinfer.config.MixInferProperties properties = new com.mixinfer.config.MixInferProperties();
        com.mixinfer.config.MixInferProperties.ProviderConfig cfg = new com.mixinfer.config.MixInferProperties.ProviderConfig();
        cfg.setName(provider.name());
        cfg.setBaseUrl("http://unused");
        cfg.setApiKey("k");
        properties.setProviders(List.of(cfg));

        com.mixinfer.config.MixInferProperties.RouteConfig route = new com.mixinfer.config.MixInferProperties.RouteConfig();
        route.setModel("gpt-4o-mini");
        route.setProvider(provider.name());
        properties.setRoutes(List.of(route));

        ModelRouter router = new ModelRouter(properties);

        return new ChatCompletionService(
                new OpenAIToLlmConverter(),
                new LlmToOpenAIConverter(),
                new LlmToOpenAIStreamConverter(objectMapper),
                router,
                registry,
                new com.mixinfer.streaming.StreamingResponseWriter(objectMapper),
                new UsageRecorder() {
                    @Override
                    public void record(UsageRecord record) {
                        // 测试不需要真正记录
                    }
                });
    }

    @Test
    void should_write_error_event_when_upstream_fails_mid_stream() throws IOException {
        LlmStream brokenStream = new LlmStream() {
            private int calls = 0;

            @Override
            public LlmStreamChunk next() throws IOException {
                if (calls++ == 0) {
                    return LlmStreamChunk.builder()
                            .id("c1")
                            .model("gpt-4o-mini")
                            .delta(com.mixinfer.domain.LlmMessageDelta.builder()
                                    .content(List.of(new ContentPart.TextPart("partial")))
                                    .build())
                            .build();
                }
                throw new IOException("upstream closed");
            }

            @Override
            public void close() {}
        };

        StreamingLlmProvider fakeProvider = new StreamingLlmProvider() {
            @Override
            public String name() {
                return "openai-compatible";
            }

            @Override
            public LlmStream invokeStream(LlmRequest request, Endpoint endpoint) {
                return brokenStream;
            }
        };

        ChatCompletionService service = buildServiceWithProvider(fakeProvider);

        // Capture all bytes written to the response via a real ServletOutputStream.
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        jakarta.servlet.ServletOutputStream out = new jakarta.servlet.ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(jakarta.servlet.WriteListener writeListener) {
                // No-op.
            }

            @Override
            public void write(int b) {
                buf.write(b);
            }
        };

        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(out);

        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setModel("gpt-4o-mini");
        request.setStream(true);
        request.setMessages(List.of(new OpenAIMessage("user", "hi")));

        assertThatThrownBy(() -> service.handleStream(request, response))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("upstream closed");

        assertThat(buf.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("upstream_stream_error");
    }
}