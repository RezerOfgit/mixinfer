package com.mixinfer.streaming;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SseEventParserTest {

    @Test
    void should_parse_single_data_event() throws IOException {
        SseEventParser parser = new SseEventParser(stream("data: hello\n\n"));

        assertThat(parser.nextData()).isEqualTo("hello");
        assertThat(parser.nextData()).isNull();
    }

    @Test
    void should_parse_multiple_events() throws IOException {
        SseEventParser parser =
                new SseEventParser(stream("data: first\n\ndata: second\n\n"));

        assertThat(parser.nextData()).isEqualTo("first");
        assertThat(parser.nextData()).isEqualTo("second");
        assertThat(parser.nextData()).isNull();
    }

    @Test
    void should_parse_openai_style_json_payload() throws IOException {
        SseEventParser parser = new SseEventParser(
                stream("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"));

        assertThat(parser.nextData())
                .isEqualTo("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}");
    }

    @Test
    void should_handle_done_marker() throws IOException {
        SseEventParser parser = new SseEventParser(stream("data: [DONE]\n\n"));

        assertThat(parser.nextData()).isEqualTo("[DONE]");
        assertThat(parser.nextData()).isNull();
    }

    @Test
    void should_handle_crlf_line_endings() throws IOException {
        SseEventParser parser = new SseEventParser(stream("data: hello\r\n\r\n"));

        assertThat(parser.nextData()).isEqualTo("hello");
    }

    @Test
    void should_ignore_comment_lines() throws IOException {
        SseEventParser parser =
                new SseEventParser(stream(": heartbeat\n\ndata: real\n\n"));

        assertThat(parser.nextData()).isEqualTo("real");
    }

    @Test
    void should_strip_single_leading_space_in_value() throws IOException {
        SseEventParser parser = new SseEventParser(stream("data:  two-spaces\n\n"));

        // Only the first space after the colon is stripped per spec.
        assertThat(parser.nextData()).isEqualTo(" two-spaces");
    }

    @Test
    void should_join_multiline_data_with_newline() throws IOException {
        SseEventParser parser =
                new SseEventParser(stream("data: line1\ndata: line2\n\n"));

        assertThat(parser.nextData()).isEqualTo("line1\nline2");
    }

    @Test
    void should_return_null_on_empty_stream() throws IOException {
        SseEventParser parser = new SseEventParser(stream(""));

        assertThat(parser.nextData()).isNull();
    }

    @Test
    void should_return_data_without_trailing_blank_line() throws IOException {
        SseEventParser parser =
                new SseEventParser(stream("data: no-newline-at-end"));

        assertThat(parser.nextData()).isEqualTo("no-newline-at-end");
    }

    @Test
    void should_ignore_event_and_id_fields() throws IOException {
        SseEventParser parser = new SseEventParser(
                stream("event: message\nid: 123\ndata: payload\n\n"));

        assertThat(parser.nextData()).isEqualTo("payload");
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}