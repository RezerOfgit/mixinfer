package com.mixinfer.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Incremental message content in an OpenAI streaming chunk.
 * Only text content is supported in V0.2.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIMessageDelta {

    private String role;
    private String content;
}