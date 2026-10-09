package com.mixinfer.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.converter.LlmToOpenAIConverter;
import com.mixinfer.converter.OpenAIStreamConverter;
import com.mixinfer.domain.LlmRequest;
import com.mixinfer.exception.ProviderException;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.router.Endpoint;
import com.mixinfer.streaming.SseEventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Streams responses from any OpenAI-compatible upstream using the
 * JDK {@link HttpClient}. Non-streaming calls remain in
 * {@link OpenAICompatibleProvider}.
 */
@Component
public class OpenAICompatibleStreamingProvider implements StreamingLlmProvider {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleStreamingProvider.class);

    private final RestClientFactory restClientFactory;
    private final ObjectMapper objectMapper;
    private final LlmToOpenAIConverter outboundConverter;
    private final OpenAIStreamConverter streamConverter;

    public OpenAICompatibleStreamingProvider(RestClientFactory restClientFactory,
                                             ObjectMapper objectMapper,
                                             LlmToOpenAIConverter outboundConverter,
                                             OpenAIStreamConverter streamConverter) {
        this.restClientFactory = restClientFactory;
        this.objectMapper = objectMapper;
        this.outboundConverter = outboundConverter;
        this.streamConverter = streamConverter;
    }

    @Override
    public LlmStream invokeStream(LlmRequest request, Endpoint endpoint) {
        OpenAIChatRequest upstreamRequest = outboundConverter.toOpenAIRequest(request);
        upstreamRequest.setStream(true);

        String json;
        try {
            json = objectMapper.writeValueAsString(upstreamRequest);
        } catch (IOException e) {
            throw new ProviderException("Failed to serialize request body", e);
        }

        HttpClient httpClient = restClientFactory.httpClient(endpoint);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.getBaseUrl() + CHAT_COMPLETIONS_PATH))
                .header("Authorization", "Bearer " + endpoint.getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
//                .timeout(endpoint.getIdleTimeout())
                .timeout(endpoint.getRequestTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        log.debug("Opening upstream stream to {}", endpoint.getBaseUrl());

        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException(
                        "Upstream returned HTTP " + response.statusCode(),
                        response.statusCode());
            }

            return new HttpLlmStream(
                    new SseEventParser(response.body()),
                    objectMapper,
                    streamConverter);

        } catch (IOException e) {
            throw new ProviderException("Failed to open upstream stream: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted while opening upstream stream", e);
        }
    }

    @Override
    public String name() {
        return "openai-compatible";
    }
}