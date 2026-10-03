package com.mixinfer.converter;

import com.mixinfer.domain.*;
import com.mixinfer.openai.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts a unified internal response into an OpenAI-compatible response.
 */
@Component
public class LlmToOpenAIConverter {

    public OpenAIChatResponse toOpenAIResponse(LlmResponse source) {
        OpenAIChatResponse target = new OpenAIChatResponse();
        target.setId(source.getId());
        target.setCreated(Instant.now().getEpochSecond());
        target.setModel(source.getModel());
        target.setChoices(source.getChoices().stream()
                .map(this::toOpenAIChoice)
                .toList());
        if (source.getUsage() != null) {
            OpenAIUsage usage = new OpenAIUsage();
            usage.setPromptTokens(source.getUsage().getPromptTokens());
            usage.setCompletionTokens(source.getUsage().getCompletionTokens());
            usage.setTotalTokens(source.getUsage().getTotalTokens());
            target.setUsage(usage);
        }
        return target;
    }

    private OpenAIChoice toOpenAIChoice(LlmChoice source) {
        OpenAIChoice target = new OpenAIChoice();
        target.setIndex(source.getIndex());
        target.setFinishReason(source.getFinishReason());

        OpenAIMessage message = new OpenAIMessage();
        message.setRole(source.getMessage().getRole());
        message.setContent(extractText(source.getMessage().getContent()));
        target.setMessage(message);

        return target;
    }

    private String extractText(List<ContentPart> parts) {
        if (parts == null) {
            return null;
        }
        return parts.stream()
                .filter(p -> p instanceof ContentPart.TextPart)
                .map(p -> ((ContentPart.TextPart) p).text())
                .collect(Collectors.joining());
    }

    public OpenAIChatRequest toOpenAIRequest(LlmRequest source) {
        OpenAIChatRequest target = new OpenAIChatRequest();
        target.setModel(source.getModel());
        target.setTemperature(source.getTemperature());
        target.setMaxTokens(source.getMaxTokens());
        target.setStream(Boolean.TRUE.equals(source.getStream()));
        target.setMessages(source.getMessages().stream()
                .map(this::toOpenAIMessage)
                .toList());
        return target;
    }

    private OpenAIMessage toOpenAIMessage(LlmMessage source) {
        String content = source.getContent() == null
                ? null
                : source.getContent().stream()
                  .filter(p -> p instanceof ContentPart.TextPart)
                  .map(p -> ((ContentPart.TextPart) p).text())
                  .collect(Collectors.joining());

        return new OpenAIMessage(source.getRole(), content);
    }
}
