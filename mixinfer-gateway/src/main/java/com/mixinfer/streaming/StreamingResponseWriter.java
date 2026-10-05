package com.mixinfer.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Writes Server-Sent Events to an {@link HttpServletResponse}.
 *
 * <p>Owns the SSE framing: the {@code data:} prefix, event separators,
 * and the {@code [DONE]} terminator. Callers only provide the payload.
 */
@Component
public class StreamingResponseWriter {

    private static final String SSE_CONTENT_TYPE = "text/event-stream";
    private static final String SSE_DATA_PREFIX = "data: ";
    private static final String SSE_EVENT_SEPARATOR = "\n\n";
    private static final String SSE_DONE = "data: [DONE]\n\n";

    private final ObjectMapper objectMapper;

    public StreamingResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Prepares the response for SSE. Must be called before any data is
     * written and before the response is committed.
     */
    public void prepare(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(SSE_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader(HttpHeaders.CONNECTION, "keep-alive");
        // Disable buffering in reverse proxies (e.g. Nginx).
        response.setHeader("X-Accel-Buffering", "no");
    }

    public void writeData(HttpServletResponse response, String data) throws IOException {
        PrintWriter writer = response.getWriter();
        writer.write(SSE_DATA_PREFIX);
        writer.write(data);
        writer.write(SSE_EVENT_SEPARATOR);
        writer.flush();
    }

    public void writeDone(HttpServletResponse response) throws IOException {
        PrintWriter writer = response.getWriter();
        writer.write(SSE_DONE);
        writer.flush();
    }

    /**
     * Writes an error as an SSE event. Only callable before the response
     * is closed; if the client has already disconnected, this will throw.
     */
    public void writeError(HttpServletResponse response, String code, String message)
            throws IOException {
        Map<String, Object> payload = Map.of(
                "error", Map.of(
                        "message", message,
                        "type", "stream_error",
                        "code", code));
        try {
            writeData(response, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            // Fall back to a minimal error event so the client sees something.
            writeData(response, "{\"error\":{\"message\":\"stream error\"}}");
        }
    }
}