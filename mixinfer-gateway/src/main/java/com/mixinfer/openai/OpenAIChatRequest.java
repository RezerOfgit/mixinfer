package com.mixinfer.openai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * OpenAI-compatible chat completion request.
 * Mirrors the public API at /v1/chat/completions.
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OpenAIChatRequest {

    private String model;
    private List<OpenAIMessage> messages;
    private Double temperature;
    private Integer maxTokens;
    private Boolean stream;
}
