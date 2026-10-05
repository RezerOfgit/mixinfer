package com.mixinfer.provider;

import com.mixinfer.exception.MixInferException;
import com.mixinfer.exception.ProviderException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds all {@link LlmProvider} and {@link StreamingLlmProvider}
 * implementations discovered by Spring.
 *
 * <p>Streaming and non-streaming providers are registered under the same
 * name, because a route references a logical provider by name and both
 * invocation modes must resolve to the same logical provider.
 */
@Component
public class ProviderRegistry {

    private final Map<String, LlmProvider> providers;
    private final Map<String, StreamingLlmProvider> streamingProviders;

    public ProviderRegistry(List<LlmProvider> providers,
                            List<StreamingLlmProvider> streamingProviders) {
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(LlmProvider::name, Function.identity()));
        this.streamingProviders = streamingProviders.stream()
                .collect(Collectors.toUnmodifiableMap(
                        StreamingLlmProvider::name, Function.identity()));
    }

    /**
     * Look up a non-streaming provider by name.
     *
     * @throws ProviderException if no provider with the given name exists
     */
    public LlmProvider get(String name) {
        LlmProvider provider = providers.get(name);
        if (provider == null) {
            throw new ProviderException("Unknown provider: " + name);
        }
        return provider;
    }

    /**
     * Look up a streaming provider by name.
     *
     * @throws MixInferException if the provider exists but does not support streaming
     */
    public StreamingLlmProvider getStreaming(String name) {
        StreamingLlmProvider provider = streamingProviders.get(name);
        if (provider == null) {
            throw new MixInferException(
                    "Provider '" + name + "' does not support streaming");
        }
        return provider;
    }
}