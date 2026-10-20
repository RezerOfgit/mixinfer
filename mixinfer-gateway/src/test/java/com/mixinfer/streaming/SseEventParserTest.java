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

    @Test
    void should_parse_large_data_payload_without_truncation() throws IOException {
        // 64 KB single-line payload — larger than any typical internal
        // buffer, and close to the upper end of real LLM tool-call results.
        String largeContent = "x".repeat(64 * 1024);
        String payload = "{\"content\":\"" + largeContent + "\"}";

        SseEventParser parser = new SseEventParser(
                stream("data: " + payload + "\n\n"));

        String result = parser.nextData();

        assertThat(result).isEqualTo(payload);
        assertThat(result.length()).isGreaterThan(64 * 1024);
    }

    @Test
    void should_return_empty_string_for_empty_data_value() throws IOException {
        // Two cases:
        //  - "data:" followed immediately by newline  -> data is ""
        //  - "data: " (one space, then newline)       -> space stripped, data is ""
        // Neither is "no data"; both must produce an empty event, not null.
        SseEventParser parser = new SseEventParser(
                stream("data:\n\ndata: \n\n"));

        assertThat(parser.nextData()).isEqualTo("");
        assertThat(parser.nextData()).isEqualTo("");
        assertThat(parser.nextData()).isNull();
    }

    @Test
    void should_preserve_colons_inside_data_value() throws IOException {
        // Only the first colon separates the field name from the value.
        // URLs and timestamps inside the JSON payload must survive intact.
        SseEventParser parser = new SseEventParser(
                stream("data: {\"url\":\"http://example.com:8080/path\","
                        + "\"time\":\"12:34:56\"}\n\n"));

        assertThat(parser.nextData())
                .isEqualTo("{\"url\":\"http://example.com:8080/path\","
                        + "\"time\":\"12:34:56\"}");
    }

    @Test
    void should_handle_utf8_multibyte_characters() throws IOException {
        // Chinese characters (3 UTF-8 bytes each) and emoji
        // (surrogate pairs in Java, 4 UTF-8 bytes) must round-trip.
        SseEventParser parser = new SseEventParser(
                stream("data: 你好 🌍 世界\n\n"));

        assertThat(parser.nextData()).isEqualTo("你好 🌍 世界");
    }

    @Test
    void should_ignore_consecutive_blank_lines() throws IOException {
        // Multiple blank lines between events must not produce empty events.
        // Some upstreams pad the stream with extra newlines.
        SseEventParser parser = new SseEventParser(
                stream("data: a\n\n\ndata: b\n\n"));

        assertThat(parser.nextData()).isEqualTo("a");
        assertThat(parser.nextData()).isEqualTo("b");
        assertThat(parser.nextData()).isNull();
    }
}