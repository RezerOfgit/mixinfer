package com.mixinfer.converter;

import com.mixinfer.domain.ContentPart;
import com.mixinfer.domain.LlmMessage;
import com.mixinfer.domain.LlmRequest;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts an OpenAI-compatible request into the unified internal model.
 */
@Component
public class OpenAIToLlmConverter {

    public LlmRequest toLlmRequest(OpenAIChatRequest source) {
        List<LlmMessage> messages = source.getMessages().stream()
                .map(this::toLlmMessage)
                .toList();

        return LlmRequest.builder()
                .model(source.getModel())
                .messages(messages)
                .temperature(source.getTemperature())
                .maxTokens(source.getMaxTokens())
                .stream(Boolean.TRUE.equals(source.getStream()))
                .build();
    }

    private LlmMessage toLlmMessage(OpenAIMessage source) {
        List<ContentPart> content = source.getContent() == null
                ? List.of()
                : List.of(new ContentPart.TextPart(source.getContent()));

        return LlmMessage.builder()
                .role(source.getRole())
                .content(content)
                .build();
    }
}
