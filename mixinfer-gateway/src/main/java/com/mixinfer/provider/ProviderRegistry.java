package com.mixinfer.provider;

import com.mixinfer.exception.ProviderException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds all {@link LlmProvider} implementations discovered by Spring.
 * Used by the service layer to resolve a provider by name.
 */
@Component
public class ProviderRegistry {

    private final Map<String, LlmProvider> providers;

    public ProviderRegistry(List<LlmProvider> providers) {
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(LlmProvider::name, Function.identity()));
    }

    /**
     * Look up a provider by its name.
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
}
