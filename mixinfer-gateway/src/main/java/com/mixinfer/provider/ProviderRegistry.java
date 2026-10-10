package com.mixinfer.provider;

import com.mixinfer.config.MixInferProperties;
import com.mixinfer.exception.MixInferException;
import com.mixinfer.exception.ProviderException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a configured provider name to its implementation.
 *
 * <p>Two names coexist in V0.3:
 * <ul>
 *   <li><b>Instance name</b> — declared in {@code mixinfer.providers[].name},
 *       referenced by routes. Multiple instances may share one implementation
 *       (e.g. two OpenAI-compatible upstreams).</li>
 *   <li><b>Type name</b> — returned by {@link LlmProvider#name()}, identifies
 *       the implementation. Declared in {@code mixinfer.providers[].type}.</li>
 * </ul>
 *
 * <p>This class maps instance names to type names using configuration, then
 * looks up the implementation by type.
 */
@Component
public class ProviderRegistry {

    private final Map<String, LlmProvider> providersByType;
    private final Map<String, StreamingLlmProvider> streamingByType;
    private final Map<String, MixInferProperties.ProviderConfig> configsByName;

    public ProviderRegistry(MixInferProperties properties,
                            List<LlmProvider> providers,
                            List<StreamingLlmProvider> streamingProviders) {
        this.providersByType = providers.stream()
                .collect(Collectors.toUnmodifiableMap(LlmProvider::name, Function.identity()));
        this.streamingByType = streamingProviders.stream()
                .collect(Collectors.toUnmodifiableMap(
                        StreamingLlmProvider::name, Function.identity()));
        this.configsByName = properties.getProviders().stream()
                .collect(Collectors.toUnmodifiableMap(
                        MixInferProperties.ProviderConfig::getName, Function.identity()));
    }

    public LlmProvider get(String instanceName) {
        String type = resolveType(instanceName);
        LlmProvider provider = providersByType.get(type);
        if (provider == null) {
            throw new ProviderException(
                    "No implementation registered for type: " + type);
        }
        return provider;
    }

    public StreamingLlmProvider getStreaming(String instanceName) {
        String type = resolveType(instanceName);
        StreamingLlmProvider provider = streamingByType.get(type);
        if (provider == null) {
            throw new MixInferException(
                    "Provider '" + instanceName + "' does not support streaming");
        }
        return provider;
    }

    private String resolveType(String instanceName) {
        MixInferProperties.ProviderConfig config = configsByName.get(instanceName);
        if (config == null) {
            throw new ProviderException("Unknown provider: " + instanceName);
        }
        return config.getType();
    }
}