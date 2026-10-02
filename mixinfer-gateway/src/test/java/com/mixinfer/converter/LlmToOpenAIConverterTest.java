package com.mixinfer.converter;

import com.mixinfer.domain.ContentPart;
import com.mixinfer.domain.LlmChoice;
import com.mixinfer.domain.LlmMessage;
import com.mixinfer.domain.LlmResponse;
import com.mixinfer.domain.LlmUsage;
import com.mixinfer.openai.OpenAIChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmToOpenAIConverterTest {

    private final LlmToOpenAIConverter converter = new LlmToOpenAIConverter();

    @Test
    void should_convert_response_with_usage() {
        LlmResponse source = LlmResponse.builder()
                .id("chatcmpl-123")
                .model("gpt-4o-mini")
                .choices(List.of(LlmChoice.builder()
                        .index(0)
                        .finishReason("stop")
                        .message(LlmMessage.builder()
                                .role("assistant")
                                .content(List.of(new ContentPart.TextPart("hi there")))
                                .build())
                        .build()))
                .usage(LlmUsage.builder()
                        .promptTokens(5)
                        .completionTokens(3)
                        .totalTokens(8)
                        .build())
                .build();

        OpenAIChatResponse result = converter.toOpenAIResponse(source);

        assertThat(result.getId()).isEqualTo("chatcmpl-123");
        assertThat(result.getObject()).isEqualTo("chat.completion");
        assertThat(result.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(result.getChoices()).hasSize(1);
        assertThat(result.getChoices().get(0).getMessage().getContent()).isEqualTo("hi there");
        assertThat(result.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(8);
        assertThat(result.getCreated()).isPositive();
    }

    @Test
    void should_handle_missing_usage() {
        LlmResponse source = LlmResponse.builder()
                .id("chatcmpl-456")
                .model("gpt-4o-mini")
                .choices(List.of(LlmChoice.builder()
                        .index(0)
                        .message(LlmMessage.builder()
                                .role("assistant")
                                .content(List.of(new ContentPart.TextPart("ok")))
                                .build())
                        .build()))
                .build();

        OpenAIChatResponse result = converter.toOpenAIResponse(source);

        assertThat(result.getUsage()).isNull();
    }
}