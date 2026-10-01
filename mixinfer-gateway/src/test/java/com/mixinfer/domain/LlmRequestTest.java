package com.mixinfer.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Re-zero
 * @version 1.0
 */
class LlmRequestTest {

    @Test
    void should_build_request_with_text_message() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(
                        LlmMessage.builder()
                                .role("user")
                                .content(List.of(new ContentPart.TextPart("hello")))
                                .build()
                ))
                .temperature(0.7)
                .stream(false)
                .build();

        assertThat(request.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(request.getMessages()).hasSize(1);
    }
}
