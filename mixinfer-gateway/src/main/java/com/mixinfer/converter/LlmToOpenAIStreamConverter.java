package com.mixinfer.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.domain.ContentPart;
import com.mixinfer.domain.LlmStreamChunk;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts an internal {@link LlmStreamChunk} into an OpenAI-compatible
 * SSE data payload (the JSON string that follows "data: ").
 *
 * <p>This is the outbound counterpart of {@link OpenAIStreamConverter}.
 */
@Component
public class LlmToOpenAIStreamConverter {

    private final ObjectMapper objectMapper;

    public LlmToOpenAIStreamConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toSseData(LlmStreamChunk source) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", source.getId());
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", Instant.now().getEpochSecond());
        chunk.put("model", source.getModel());

        Map<String, Object> delta = new LinkedHashMap<>();
        if (source.getDelta() != null) {
            if (source.getDelta().getRole() != null) {
                delta.put("role", source.getDelta().getRole());
            }
            String text = extractText(source.getDelta().getContent());
            if (text != null && !text.isEmpty()) {
                delta.put("content", text);
            }
        }

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", source.getIndex());
        choice.put("delta", delta);
        if (source.getFinishReason() != null) {
            choice.put("finish_reason", source.getFinishReason());
        }

        chunk.put("choices", List.of(choice));

        if (source.getUsage() != null) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", source.getUsage().getPromptTokens());
            usage.put("completion_tokens", source.getUsage().getCompletionTokens());
            usage.put("total_tokens", source.getUsage().getTotalTokens());
            chunk.put("usage", usage);
        }

        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize stream chunk", e);
        }
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
}