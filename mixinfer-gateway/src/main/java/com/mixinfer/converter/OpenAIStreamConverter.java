package com.mixinfer.converter;

import com.mixinfer.domain.ContentPart;
import com.mixinfer.domain.LlmMessageDelta;
import com.mixinfer.domain.LlmStreamChunk;
import com.mixinfer.domain.LlmUsage;
import com.mixinfer.openai.OpenAIStreamChoice;
import com.mixinfer.openai.OpenAIStreamChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts OpenAI streaming chunks into provider-neutral IR chunks.
 */
@Component
public class OpenAIStreamConverter {

    public LlmStreamChunk toLlmStreamChunk(OpenAIStreamChunk source) {
        OpenAIStreamChoice choice = firstChoice(source);

        LlmMessageDelta delta = choice == null ? null : toDelta(choice);
        String finishReason = choice == null ? null : choice.getFinishReason();

        return LlmStreamChunk.builder()
                .id(source.getId())
                .model(source.getModel())
                .index(choice == null ? 0 : choice.getIndex())
                .delta(delta)
                .finishReason(finishReason)
                .usage(toUsage(source))
                .build();
    }

    private OpenAIStreamChoice firstChoice(OpenAIStreamChunk source) {
        if (source.getChoices() == null || source.getChoices().isEmpty()) {
            return null;
        }
        return source.getChoices().get(0);
    }

    private LlmMessageDelta toDelta(OpenAIStreamChoice choice) {
        if (choice.getDelta() == null) {
            return null;
        }
        String content = choice.getDelta().getContent();
        List<ContentPart> parts = content == null
                ? List.of()
                : List.of(new ContentPart.TextPart(content));

        return LlmMessageDelta.builder()
                .role(choice.getDelta().getRole())
                .content(parts)
                .build();
    }

    private LlmUsage toUsage(OpenAIStreamChunk source) {
        if (source.getUsage() == null) {
            return null;
        }
        return LlmUsage.builder()
                .promptTokens(source.getUsage().getPromptTokens())
                .completionTokens(source.getUsage().getCompletionTokens())
                .totalTokens(source.getUsage().getTotalTokens())
                .build();
    }
}