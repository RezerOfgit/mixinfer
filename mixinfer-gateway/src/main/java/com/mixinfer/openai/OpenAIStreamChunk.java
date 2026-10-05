package com.mixinfer.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * One chunk of an OpenAI-compatible streaming response.
 * Parsed from a single {@code data: {...}} SSE event.
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIStreamChunk {

    private String id;
    private String object;
    private Long created;
    private String model;
    private List<OpenAIStreamChoice> choices;
    private OpenAIUsage usage;
}