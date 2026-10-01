package com.mixinfer.domain;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * The unified internal representation of an LLM chat response.
 * Provider-specific fields go into {@code extensions}.
 */
@Value
@Builder
public class LlmResponse {

    /** Response identifier assigned by the upstream provider. */
    String id;

    /** The logical model name that produced this response. */
    String model;

    /** Generated choices, ordered by index. */
    List<LlmChoice> choices;

    /** Token usage reported by the upstream provider. */
    LlmUsage usage;

    /** Escape hatch for provider-specific response fields. */
    Map<String, Object> extensions;
}
