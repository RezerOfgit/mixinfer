package com.mixinfer.streaming;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Parses Server-Sent Events (SSE) from an {@link InputStream}.
 *
 * <p>Only the {@code data} field is surfaced. Other fields
 * ({@code event}, {@code id}, {@code retry}) are consumed but ignored
 * in V0.2.
 *
 * <p>SSE spec:
 * https://html.spec.whatwg.org/multipage/server-sent-events.html
 */
public class SseEventParser implements AutoCloseable {

    private static final String DATA_FIELD = "data";

    private final BufferedReader reader;
    private final InputStream inputStream;

    public SseEventParser(InputStream inputStream) {
        this.inputStream = inputStream;
        this.reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    /**
     * Reads the next event's data field.
     *
     * @return the data value, or null if the stream is exhausted
     * @throws IOException if the underlying stream fails
     */
    public String nextData() throws IOException {
        StringBuilder data = null;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // Empty line terminates an event.
                if (data != null) {
                    return data.toString();
                }
                continue;
            }

            if (line.startsWith(":")) {
                // Comment line (e.g. heartbeat). Ignore.
                continue;
            }

            int colon = line.indexOf(':');
            if (colon < 0) {
                // Field line without colon: field name with empty value.
                continue;
            }

            String field = line.substring(0, colon);
            String value = line.substring(colon + 1);
            // A single leading space in the value is stripped per spec.
            if (!value.isEmpty() && value.charAt(0) == ' ') {
                value = value.substring(1);
            }

            if (DATA_FIELD.equals(field)) {
                if (data == null) {
                    data = new StringBuilder(value);
                } else {
                    data.append('\n').append(value);
                }
            }
        }

        // Stream ended without a trailing blank line.
        return data == null ? null : data.toString();
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}