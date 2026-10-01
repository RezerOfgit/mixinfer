package com.mixinfer.domain;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * The unified internal representation of an LLM chat request.
 * Provider-specific fields go into {@code extensions}.
 */
@Value
@Builder
public class LlmRequest {

    /** Logical model name (e.g. "gpt-4o-mini"), not the provider-native name. */
    String model;

    /** Conversation history. */
    List<LlmMessage> messages;

    /** Sampling temperature. */
    Double temperature;

    /** Maximum tokens to generate. */
    Integer maxTokens;

    /** Whether to stream the response. V0.1 always false. */
    Boolean stream;

    /**
     * Escape hatch for provider-specific parameters.
     * Do NOT put business logic here; only pass-through fields.
     */
    Map<String, Object> extensions;
}