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
        LlmProvider provider = providerRegistry.get(endpoint.getProvider());
        LlmResponse llmResponse = provider.invoke(llmRequest, endpoint);
        return outboundConverter.toOpenAIResponse(llmResponse);
    }
}