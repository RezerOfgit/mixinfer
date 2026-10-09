package com.mixinfer.provider;

import com.mixinfer.converter.LlmToOpenAIConverter;
import com.mixinfer.converter.OpenAIToLlmConverter;
import com.mixinfer.domain.LlmRequest;
import com.mixinfer.domain.LlmResponse;
import com.mixinfer.exception.ProviderException;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIChatResponse;
import com.mixinfer.router.Endpoint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Generic provider for any upstream that speaks the OpenAI-compatible
 * protocol (official OpenAI, DeepSeek, Qwen, vLLM, one-api, OpenRouter, ...).
 * The concrete upstream is chosen entirely by {@link Endpoint} configuration.
 */
@Component
public class OpenAICompatibleProvider implements LlmProvider {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final RestClient restClient;
    private final OpenAIToLlmConverter inboundConverter;
    private final LlmToOpenAIConverter outboundConverter;

    public OpenAICompatibleProvider(OpenAIToLlmConverter inboundConverter,
                                    LlmToOpenAIConverter outboundConverter) {
        this.restClient = RestClient.builder().build();
        this.inboundConverter = inboundConverter;
        this.outboundConverter = outboundConverter;
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    @Override
    public LlmResponse invoke(LlmRequest request, Endpoint endpoint) {
        OpenAIChatRequest upstreamRequest = outboundConverter.toOpenAIRequest(request);

        try {
            OpenAIChatResponse upstreamResponse = restClient.post()
                    .uri(endpoint.getBaseUrl() + CHAT_COMPLETIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(upstreamRequest)
                    .retrieve()
                    .body(OpenAIChatResponse.class);

            if (upstreamResponse == null) {
                throw new ProviderException("Upstream returned an empty body");
            }
            return inboundConverter.toLlmResponse(upstreamResponse);

        } catch (HttpStatusCodeException e) {
            throw new ProviderException(
                    "Upstream returned HTTP " + e.getStatusCode().value(),
                    e.getStatusCode().value(),
                    e);
        } catch (RestClientResponseException e) {
            throw new ProviderException(
                    "Upstream returned HTTP " + e.getStatusCode().value(),
                    e.getStatusCode().value(),
                    e);
        } catch (RestClientException e) {
            throw new ProviderException("Upstream call failed: " + e.getMessage(), e);
        }
    }
}