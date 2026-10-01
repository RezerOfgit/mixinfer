package com.mixinfer.openai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * A single choice in an OpenAI chat completion response.
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OpenAIChoice {

    private int index;
    private OpenAIMessage message;
    private String finishReason;
}
