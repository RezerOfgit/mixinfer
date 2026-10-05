package com.mixinfer.converter;

import com.mixinfer.domain.ContentPart;
import com.mixinfer.domain.LlmStreamChunk;
import com.mixinfer.openai.OpenAIStreamChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIStreamConverterTest {

    private final OpenAIStreamConverter converter = new OpenAIStreamConverter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void should_parse_content_chunk() throws Exception {
        OpenAIStreamChunk source = objectMapper.readValue("""
                {"id":"chatcmpl-x","model":"gpt-4o-mini",
                 "choices":[{"index":0,"delta":{"content":"Hello"}}]}
                """, OpenAIStreamChunk.class);

        LlmStreamChunk result = converter.toLlmStreamChunk(source);

        assertThat(result.getId()).isEqualTo("chatcmpl-x");
        assertThat(result.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(result.getIndex()).isZero();
        assertThat(result.getDelta()).isNotNull();
        assertThat(result.getDelta().getContent()).hasSize(1);
        assertThat(((ContentPart.TextPart) result.getDelta().getContent().get(0)).text())
                .isEqualTo("Hello");
    }

    @Test
    void should_parse_final_chunk_with_finish_reason() throws Exception {
        OpenAIStreamChunk source = objectMapper.readValue("""
                {"id":"chatcmpl-x","model":"gpt-4o-mini",
                 "choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                """, OpenAIStreamChunk.class);

        LlmStreamChunk result = converter.toLlmStreamChunk(source);

        assertThat(result.getFinishReason()).isEqualTo("stop");
    }

    @Test
    void should_parse_usage_only_chunk() throws Exception {
        OpenAIStreamChunk source = objectMapper.readValue("""
                {"id":"chatcmpl-x","model":"gpt-4o-mini","choices":[],
                 "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
                """, OpenAIStreamChunk.class);

        LlmStreamChunk result = converter.toLlmStreamChunk(source);

        assertThat(result.getDelta()).isNull();
        assertThat(result.getUsage()).isNotNull();
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(8);
    }

    @Test
    void should_tolerate_missing_optional_fields() throws Exception {
        OpenAIStreamChunk source = objectMapper.readValue("""
                {"id":"chatcmpl-x"}
                """, OpenAIStreamChunk.class);

        LlmStreamChunk result = converter.toLlmStreamChunk(source);

        assertThat(result.getId()).isEqualTo("chatcmpl-x");
        assertThat(result.getDelta()).isNull();
        assertThat(result.getFinishReason()).isNull();
        assertThat(result.getUsage()).isNull();
    }
}