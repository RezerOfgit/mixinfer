package com.mixinfer.auth;

import com.mixinfer.config.MixInferProperties;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates client-facing API keys against the configured key list.
 */
@Component
public class ApiKeyValidator {

    private final Set<String> validKeys;

    public ApiKeyValidator(MixInferProperties properties) {
        this.validKeys = properties.getApiKeys().stream()
                .map(MixInferProperties.ApiKeyConfig::getKey)
                .filter(k -> k != null && !k.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isValid(String key) {
        return key != null && validKeys.contains(key);
    }
}