package com.mixinfer.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.converter.OpenAIStreamConverter;
import com.mixinfer.domain.LlmStreamChunk;
import com.mixinfer.openai.OpenAIStreamChunk;
import com.mixinfer.streaming.SseEventParser;

import java.io.IOException;

/**
 * An {@link LlmStream} backed by an HTTP SSE response.
 * Closing this stream closes the underlying HTTP connection.
 */
class HttpLlmStream implements LlmStream {

    private static final String DONE_MARKER = "[DONE]";

    private final SseEventParser parser;
    private final ObjectMapper objectMapper;
    private final OpenAIStreamConverter converter;

    private boolean closed = false;

    HttpLlmStream(SseEventParser parser,
                  ObjectMapper objectMapper,
                  OpenAIStreamConverter converter) {
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.converter = converter;
    }

    @Override
    public LlmStreamChunk next() throws IOException {
        if (closed) {
            return null;
        }

        String data;
        while ((data = parser.nextData()) != null) {
            if (DONE_MARKER.equals(data)) {
                return null;
            }
            try {
                OpenAIStreamChunk chunk = objectMapper.readValue(data, OpenAIStreamChunk.class);
                return converter.toLlmStreamChunk(chunk);
            } catch (IOException e) {
                throw new IOException("Failed to parse upstream SSE chunk: " + data, e);
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            parser.close();
        } catch (IOException ignored) {
            // Best-effort close; the connection will be released.
        }
    }
}