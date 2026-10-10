package com.mixinfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.config.MixInferProperties;
import com.mixinfer.converter.LlmToOpenAIConverter;
import com.mixinfer.converter.LlmToOpenAIStreamConverter;
import com.mixinfer.converter.OpenAIToLlmConverter;
import com.mixinfer.domain.LlmRequest;
import com.mixinfer.domain.LlmResponse;
import com.mixinfer.exception.ProviderException;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIMessage;
import com.mixinfer.provider.LlmProvider;
import com.mixinfer.provider.LlmStream;
import com.mixinfer.provider.ProviderRegistry;
import com.mixinfer.provider.StreamingLlmProvider;
import com.mixinfer.router.RoutePlanner;
import com.mixinfer.router.failure.DefaultFailureClassifier;
import com.mixinfer.router.health.EndpointHealthTracker;
import com.mixinfer.streaming.StreamingResponseWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCompletionServiceFailoverTest {

    @Test
    void should_failover_to_second_endpoint_on_500() {
        AtomicInteger calls = new AtomicInteger();

        LlmProvider provider = new LlmProvider() {
            @Override public String name() { return "openai-compatible"; }
            @Override public LlmResponse invoke(LlmRequest r, com.mixinfer.router.Endpoint e) {
                calls.incrementAndGet();
                if (e.getProvider().equals("primary")) {
                    throw new ProviderException("boom", 500);
                }
                return LlmResponse.builder()
                        .id("ok")
                        .model("gpt-4o-mini")
                        .choices(List.of())
                        .build();
            }
        };

        ChatCompletionService service = buildService(provider);

        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setModel("gpt-4o-mini");
        request.setMessages(List.of(new OpenAIMessage("user", "hi")));

        var response = service.handle(request);

        assertThat(response).isNotNull();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void should_not_failover_on_401() {
        AtomicInteger calls = new AtomicInteger();

        LlmProvider provider = new LlmProvider() {
            @Override public String name() { return "openai-compatible"; }
            @Override public LlmResponse invoke(LlmRequest r, com.mixinfer.router.Endpoint e) {
                calls.incrementAndGet();
                throw new ProviderException("auth", 401);
            }
        };

        ChatCompletionService service = buildService(provider);

        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setModel("gpt-4o-mini");
        request.setMessages(List.of(new OpenAIMessage("user", "hi")));

        assertThatThrownBy(() -> service.handle(request))
                .isInstanceOf(ProviderException.class);

        assertThat(calls.get()).isEqualTo(1);   // no failover
    }

    @Test
    void should_failover_streaming_open_on_500() throws Exception {
        AtomicInteger openCalls = new AtomicInteger();

        StreamingLlmProvider streamingProvider = new StreamingLlmProvider() {
            @Override public String name() { return "openai-compatible"; }
            @Override public LlmStream invokeStream(LlmRequest r, com.mixinfer.router.Endpoint e) {
                openCalls.incrementAndGet();
                if (e.getProvider().equals("primary")) {
                    throw new ProviderException("boom", 500);
                }
                return new LlmStream() {
                    @Override public com.mixinfer.domain.LlmStreamChunk next() {
                        return null;
                    }
                    @Override public void close() {}
                };
            }
        };

        ChatCompletionService service = buildStreamingService(streamingProvider);

        jakarta.servlet.http.HttpServletResponse response =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream out =
                org.mockito.Mockito.mock(jakarta.servlet.ServletOutputStream.class);
        org.mockito.Mockito.when(response.getOutputStream()).thenReturn(out);

        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setModel("gpt-4o-mini");
        request.setStream(true);
        request.setMessages(List.of(new OpenAIMessage("user", "hi")));

        service.handleStream(request, response);

        assertThat(openCalls.get()).isEqualTo(2);   // primary failed, backup succeeded
    }

    @Test
    void should_not_failover_streaming_open_on_401() throws Exception {
        AtomicInteger openCalls = new AtomicInteger();

        StreamingLlmProvider streamingProvider = new StreamingLlmProvider() {
            @Override public String name() { return "openai-compatible"; }
            @Override public LlmStream invokeStream(LlmRequest r, com.mixinfer.router.Endpoint e) {
                openCalls.incrementAndGet();
                throw new ProviderException("auth", 401);
            }
        };

        ChatCompletionService service = buildStreamingService(streamingProvider);

        jakarta.servlet.http.HttpServletResponse response =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);

        OpenAIChatRequest request = new OpenAIChatRequest();
        request.setModel("gpt-4o-mini");
        request.setStream(true);
        request.setMessages(List.of(new OpenAIMessage("user", "hi")));

        assertThatThrownBy(() -> service.handleStream(request, response))
                .isInstanceOf(ProviderException.class);

        assertThat(openCalls.get()).isEqualTo(1);   // no failover
    }

    private ChatCompletionService buildService(LlmProvider provider) {
        ObjectMapper om = new ObjectMapper();
        MixInferProperties properties = ChatCompletionServiceTestFixtures.twoTargetProperties();
        EndpointHealthTracker health = ChatCompletionServiceTestFixtures.noopHealth();
        RoutePlanner planner = ChatCompletionServiceTestFixtures.planner(properties, health);

        ProviderRegistry registry = new ProviderRegistry(
                properties,
                List.of(provider),
                List.of());

        return new ChatCompletionService(
                new OpenAIToLlmConverter(),
                new LlmToOpenAIConverter(),
                new LlmToOpenAIStreamConverter(om),
                planner,
                registry,
                new StreamingResponseWriter(om),
                r -> {},
                health,
                new DefaultFailureClassifier());
    }

    private ChatCompletionService buildStreamingService(StreamingLlmProvider provider) {
        ObjectMapper om = new ObjectMapper();

        MixInferProperties properties = new MixInferProperties();

        MixInferProperties.ProviderConfig p1 = new MixInferProperties.ProviderConfig();
        p1.setName("primary");
        p1.setBaseUrl("http://primary");
        p1.setApiKey("k1");
        MixInferProperties.ProviderConfig p2 = new MixInferProperties.ProviderConfig();
        p2.setName("backup");
        p2.setBaseUrl("http://backup");
        p2.setApiKey("k2");
        properties.setProviders(List.of(p1, p2));

        MixInferProperties.TargetConfig t1 = new MixInferProperties.TargetConfig();
        t1.setProvider("primary");
        MixInferProperties.TargetConfig t2 = new MixInferProperties.TargetConfig();
        t2.setProvider("backup");
        MixInferProperties.RouteConfig route = new MixInferProperties.RouteConfig();
        route.setModel("gpt-4o-mini");
        route.setTargets(List.of(t1, t2));
        properties.setRoutes(List.of(route));

        RoutePlanner planner = ChatCompletionServiceTestFixtures.planner(properties,
                ChatCompletionServiceTestFixtures.noopHealth());

        ProviderRegistry registry = new ProviderRegistry(
                properties,
                List.of(),
                List.of(provider));

        return new ChatCompletionService(
                new OpenAIToLlmConverter(),
                new LlmToOpenAIConverter(),
                new LlmToOpenAIStreamConverter(om),
                planner,
                registry,
                new StreamingResponseWriter(om),
                r -> {},
                ChatCompletionServiceTestFixtures.noopHealth(),
                new DefaultFailureClassifier());
    }
}