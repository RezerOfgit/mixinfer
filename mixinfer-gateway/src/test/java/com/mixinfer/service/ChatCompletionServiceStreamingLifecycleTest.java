package com.mixinfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.config.MixInferProperties;
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
import com.mixinfer.router.RoutePlanner;
import com.mixinfer.router.failure.DefaultFailureClassifier;
import com.mixinfer.router.health.EndpointHealthTracker;
import com.mixinfer.streaming.StreamingResponseWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class ChatCompletionServiceStreamingLifecycleTest {

    // ===================== 原有测试 1 =====================
    @Test
    void should_close_upstream_stream_when_client_disconnects() throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);

        LlmStream fakeStream = new LlmStream() {
            private int yielded = 0;
            @Override public LlmStreamChunk next() {
                return LlmStreamChunk.builder()
                        .id("c1").model("gpt-4o-mini")
                        .delta(com.mixinfer.domain.LlmMessageDelta.builder()
                                .content(List.of(new ContentPart.TextPart("chunk-" + (yielded++))))
                                .build())
                        .build();
            }
            @Override public void close() { closed.set(true); }
        };

        StreamingLlmProvider fakeProvider = new StreamingLlmProvider() {
            @Override public String name() { return "openai-compatible"; }
            @Override public LlmStream invokeStream(LlmRequest r, Endpoint e) { return fakeStream; }
        };

        ChatCompletionService service = buildServiceWithProvider(fakeProvider);

        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream out = Mockito.mock(jakarta.servlet.ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(out);
        doThrow(new IOException("client disconnected")).when(out).flush();

        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setModel("gpt-4o-mini");
        request.setStream(true);
        request.setMessages(List.of(new OpenAIMessage("user", "hi")));

        assertThatThrownBy(() -> service.handleStream(request, response))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("client disconnected");

        assertThat(closed).as("upstream stream must be closed").isTrue();
    }

    // ===================== 原有测试 2 =====================
    @Test
    void should_write_error_event_when_upstream_fails_mid_stream() throws IOException {
        LlmStream brokenStream = new LlmStream() {
            private int calls = 0;
            @Override public LlmStreamChunk next() throws IOException {
                if (calls++ == 0) {
                    return LlmStreamChunk.builder()
                            .id("c1").model("gpt-4o-mini")
                            .delta(com.mixinfer.domain.LlmMessageDelta.builder()
                                    .content(List.of(new ContentPart.TextPart("partial")))
                                    .build())
                            .build();
                }
                throw new IOException("upstream closed");
            }
            @Override public void close() {}
        };

        StreamingLlmProvider fakeProvider = new StreamingLlmProvider() {
            @Override public String name() { return "openai-compatible"; }
            @Override public LlmStream invokeStream(LlmRequest r, Endpoint e) { return brokenStream; }
        };

        ChatCompletionService service = buildServiceWithProvider(fakeProvider);

        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        jakarta.servlet.ServletOutputStream out = new jakarta.servlet.ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(jakarta.servlet.WriteListener l) {}
            @Override public void write(int b) { buf.write(b); }
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

    // ===================== 新增测试 1 =====================
    @Test
    void should_settle_client_disconnected_when_client_aborts() throws IOException {
        LlmStream stream = new LlmStream() {
            private int yielded = 0;
            @Override public LlmStreamChunk next() {
                return LlmStreamChunk.builder()
                        .id("c1").model("gpt-4o-mini")
                        .delta(com.mixinfer.domain.LlmMessageDelta.builder()
                                .content(List.of(new ContentPart.TextPart("chunk-" + (yielded++))))
                                .build())
                        .build();
            }
            @Override public void close() {}
        };

        StreamingLlmProvider provider = new StreamingLlmProvider() {
            @Override public String name() { return "openai-compatible"; }
            @Override public LlmStream invokeStream(LlmRequest r, Endpoint e) { return stream; }
        };

        CapturingUsageRecorder recorder = new CapturingUsageRecorder();
        ChatCompletionService service = buildServiceWithProvider(provider, recorder);

        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream out = Mockito.mock(jakarta.servlet.ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(out);
        doThrow(new IOException("broken pipe")).when(out).flush();

        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setModel("gpt-4o-mini");
        request.setStream(true);
        request.setMessages(List.of(new OpenAIMessage("user", "hi")));

        assertThatThrownBy(() -> service.handleStream(request, response))
                .isInstanceOf(IOException.class);

        assertThat(recorder.records).hasSize(1);
        assertThat(recorder.records.get(0).getReason())
                .isEqualTo("client_disconnected");
    }

    // ===================== 新增测试 2 =====================
    @Test
    void should_settle_upstream_stream_error_when_upstream_breaks() throws IOException {
        LlmStream broken = new LlmStream() {
            private int calls = 0;
            @Override public LlmStreamChunk next() throws IOException {
                if (calls++ == 0) {
                    return LlmStreamChunk.builder()
                            .id("c1").model("gpt-4o-mini")
                            .delta(com.mixinfer.domain.LlmMessageDelta.builder()
                                    .content(List.of(new ContentPart.TextPart("partial")))
                                    .build())
                            .build();
                }
                throw new IOException("upstream closed");
            }
            @Override public void close() {}
        };

        StreamingLlmProvider provider = new StreamingLlmProvider() {
            @Override public String name() { return "openai-compatible"; }
            @Override public LlmStream invokeStream(LlmRequest r, Endpoint e) { return broken; }
        };

        CapturingUsageRecorder recorder = new CapturingUsageRecorder();
        ChatCompletionService service = buildServiceWithProvider(provider, recorder);

        jakarta.servlet.ServletOutputStream out = new jakarta.servlet.ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(jakarta.servlet.WriteListener l) {}
            @Override public void write(int b) {}
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

        assertThat(recorder.records).hasSize(1);
        assertThat(recorder.records.get(0).getReason())
                .isEqualTo("upstream_stream_error");
    }

    // ===================== 辅助方法 =====================

    private ChatCompletionService buildServiceWithProvider(StreamingLlmProvider provider) {
        return buildServiceWithProvider(provider, r -> {});
    }

    private ChatCompletionService buildServiceWithProvider(
            StreamingLlmProvider provider, UsageRecorder recorder) {
        ObjectMapper om = new ObjectMapper();
        MixInferProperties properties =
                ChatCompletionServiceTestFixtures.singleTargetProperties(provider.name());
        EndpointHealthTracker health = ChatCompletionServiceTestFixtures.noopHealth();
        RoutePlanner planner = ChatCompletionServiceTestFixtures.planner(properties, health);

        ProviderRegistry registry = new ProviderRegistry(
                properties, List.of(), List.of(provider));

        return new ChatCompletionService(
                new OpenAIToLlmConverter(),
                new LlmToOpenAIConverter(),
                new LlmToOpenAIStreamConverter(om),
                planner,
                registry,
                new StreamingResponseWriter(om),
                recorder,
                health,
                new DefaultFailureClassifier(),
                om);
    }

    // ===================== 辅助类 =====================
    static final class CapturingUsageRecorder implements UsageRecorder {
        final List<UsageRecord> records = new ArrayList<>();
        @Override public void record(UsageRecord record) {
            records.add(record);
        }
    }
}