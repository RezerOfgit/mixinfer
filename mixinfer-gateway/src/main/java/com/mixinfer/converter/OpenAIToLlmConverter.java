package com.mixinfer.converter;

import com.mixinfer.domain.*;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIChatResponse;
import com.mixinfer.openai.OpenAIChoice;
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

    public LlmResponse toLlmResponse(OpenAIChatResponse source) {
        List<LlmChoice> choices = source.getChoices() == null
                ? List.of()
                : source.getChoices().stream().map(this::toLlmChoice).toList();

        LlmUsage usage = null;
        if (source.getUsage() != null) {
            usage = LlmUsage.builder()
                    .promptTokens(source.getUsage().getPromptTokens())
                    .completionTokens(source.getUsage().getCompletionTokens())
                    .totalTokens(source.getUsage().getTotalTokens())
                    .build();
        }

        return LlmResponse.builder()
                .id(source.getId())
                .model(source.getModel())
                .choices(choices)
                .usage(usage)
                .build();
    }

    private LlmChoice toLlmChoice(OpenAIChoice source) {
        List<ContentPart> content = source.getMessage() == null
                || source.getMessage().getContent() == null
                ? List.of()
                : List.of(new ContentPart.TextPart(source.getMessage().getContent()));

        return LlmChoice.builder()
                .index(source.getIndex())
                .finishReason(source.getFinishReason())
                .message(LlmMessage.builder()
                        .role(source.getMessage().getRole())
                        .content(content)
                        .build())
                .build();
    }
}
