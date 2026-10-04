package com.mixinfer.domain;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Incremental content in a streaming chunk.
 * Role is present only on the first chunk of a message.
 * Content may be empty on the final chunk.
 */
@Value
@Builder
public class LlmMessageDelta {

    String role;
    List<ContentPart> content;
}