package com.mixinfer.domain;

import lombok.Builder;
import lombok.Value;

/**
 * One chunk of a streaming response.
 * Provider-specific streaming formats are converted into this
 * provider-neutral representation.
 */
@Value
@Builder
public class LlmStreamChunk {

    /** Response id, same across all chunks of a single response. */
    String id;

    /** Logical model name. */
    String model;

    /** Choice index. V0.2 only handles index 0. */
    int index;

    /** Incremental content. May be null if the chunk only carries metadata. */
    LlmMessageDelta delta;

    /** Reason the generation stopped. Only present on the final chunk. */
    String finishReason;

    /**
     * Token usage. Only present on the final chunk, and only when the
     * upstream reports it. May be null.
     */
    LlmUsage usage;
}