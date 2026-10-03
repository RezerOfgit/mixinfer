package com.mixinfer.service;

import com.mixinfer.converter.LlmToOpenAIConverter;
import com.mixinfer.converter.OpenAIToLlmConverter;
import com.mixinfer.domain.LlmRequest;
import com.mixinfer.domain.LlmResponse;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIChatResponse;
import com.mixinfer.provider.LlmProvider;
import com.mixinfer.provider.ProviderRegistry;
import com.mixinfer.router.Endpoint;
import com.mixinfer.router.ModelRouter;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the request pipeline:
 * convert -> route -> invoke upstream -> convert back.
 */
@Service
public class ChatCompletionService {

    private final OpenAIToLlmConverter inboundConverter;
    private final LlmToOpenAIConverter outboundConverter;
    private final ModelRouter router;
    private final ProviderRegistry providerRegistry;
    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);

    public ChatCompletionService(OpenAIToLlmConverter inboundConverter,
                                 LlmToOpenAIConverter outboundConverter,
                                 ModelRouter router,
                                 ProviderRegistry providerRegistry) {
        this.inboundConverter = inboundConverter;
        this.outboundConverter = outboundConverter;
        this.router = router;
        this.providerRegistry = providerRegistry;
    }

    public OpenAIChatResponse handle(OpenAIChatRequest request) {
        LlmRequest llmRequest = inboundConverter.toLlmRequest(request);
        Endpoint endpoint = router.route(llmRequest.getModel());

        log.info("Routing model={} to provider={}", llmRequest.getModel(), endpoint.getProvider());

        LlmProvider provider = providerRegistry.get(endpoint.getProvider());
        LlmResponse llmResponse = provider.invoke(llmRequest, endpoint);

        log.info("Upstream responded in model={}, tokens={}",
                llmResponse.getModel(),
                llmResponse.getUsage() == null ? "n/a" : llmResponse.getUsage().getTotalTokens());

        return outboundConverter.toOpenAIResponse(llmResponse);
    }
}