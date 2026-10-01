package com.mixinfer.openai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * Token usage reported by an OpenAI-compatible endpoint.
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OpenAIUsage {

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
}
