package com.mixinfer.provider;

import com.mixinfer.domain.LlmRequest;
import com.mixinfer.router.Endpoint;

/**
 * SPI for providers that support streaming responses.
 *
 * <p>Kept separate from {@link LlmProvider} so that providers that cannot
 * stream are not forced to implement it. The {@code name()} must match
 * the corresponding {@link LlmProvider#name()} for the same endpoint.
 */
public interface StreamingLlmProvider {

    /**
     * Unique name of this provider, matching the {@code provider} field
     * in route configuration and the non-streaming {@link LlmProvider}.
     */
    String name();

    /**
     * Opens a streaming request to the upstream provider.
     * The returned {@link LlmStream} must be closed by the caller.
     */
    LlmStream invokeStream(LlmRequest request, Endpoint endpoint);
}