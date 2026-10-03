package com.mixinfer.provider;

import com.mixinfer.domain.LlmRequest;
import com.mixinfer.domain.LlmResponse;
import com.mixinfer.router.Endpoint;

/**
 * SPI for a specific kind of upstream LLM provider.
 * Implementations are discovered by Spring and registered in {@link ProviderRegistry}.
 */
public interface LlmProvider {

    /**
     * Unique name of this provider, e.g. "openai-compatible".
     * Must match the {@code provider} field in route configuration.
     */
    String name();

    /**
     * Invoke the upstream provider and return a unified response.
     *
     * @throws com.mixinfer.exception.ProviderException if the upstream call fails
     */
    LlmResponse invoke(LlmRequest request, Endpoint endpoint);
}
