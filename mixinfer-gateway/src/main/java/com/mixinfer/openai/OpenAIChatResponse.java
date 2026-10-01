package com.mixinfer.openai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * OpenAI-compatible chat completion response.
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OpenAIChatResponse {

    private String id;
    private String object = "chat.completion";
    private Long created;
    private String model;
    private List<OpenAIChoice> choices;
    private OpenAIUsage usage;
}
