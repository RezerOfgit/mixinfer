package com.mixinfer.converter;

import com.mixinfer.domain.ContentPart;
import com.mixinfer.domain.LlmRequest;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIToLlmConverterTest {

    private final OpenAIToLlmConverter converter = new OpenAIToLlmConverter();

    @Test
    void should_convert_simple_request() {
        OpenAIChatRequest source = new OpenAIChatRequest();
        source.setModel("gpt-4o-mini");
        source.setTemperature(0.7);
        source.setStream(false);
        source.setMessages(List.of(new OpenAIMessage("user", "hello")));

        LlmRequest result = converter.toLlmRequest(source);

        assertThat(result.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(result.getTemperature()).isEqualTo(0.7);
        assertThat(result.getStream()).isFalse();
        assertThat(result.getMessages()).hasSize(1);

        var message = result.getMessages().get(0);
        assertThat(message.getRole()).isEqualTo("user");
        assertThat(message.getContent()).hasSize(1);
        assertThat(((ContentPart.TextPart) message.getContent().get(0)).text())
                .isEqualTo("hello");
    }

    @Test
    void should_default_stream_to_false_when_absent() {
        OpenAIChatRequest source = new OpenAIChatRequest();
        source.setModel("gpt-4o-mini");
        source.setMessages(List.of(new OpenAIMessage("user", "hi")));
        source.setStream(null);

        LlmRequest result = converter.toLlmRequest(source);

        assertThat(result.getStream()).isFalse();
    }
}