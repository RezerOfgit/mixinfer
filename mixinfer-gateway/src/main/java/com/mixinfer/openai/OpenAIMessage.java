package com.mixinfer.openai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single message in an OpenAI chat request or response.
 * V0.1 only supports plain text content.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenAIMessage {

    private String role;
    private String content;
}
