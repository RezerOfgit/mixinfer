package com.mixinfer.service;

import com.mixinfer.converter.LlmToOpenAIConverter;
import com.mixinfer.converter.LlmToOpenAIStreamConverter;
import com.mixinfer.converter.OpenAIToLlmConverter;
import com.mixinfer.domain.LlmRequest;
import com.mixinfer.domain.LlmResponse;
import com.mixinfer.domain.LlmStreamChunk;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIChatResponse;
import com.mixinfer.provider.LlmProvider;
import com.mixinfer.provider.LlmStream;
import com.mixinfer.provider.ProviderRegistry;
import com.mixinfer.provider.StreamingLlmProvider;
import com.mixinfer.router.Endpoint;
import com.mixinfer.router.ModelRouter;
import com.mixinfer.streaming.StreamingResponseWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Orchestrates the request pipeline:
 * convert -> route -> invoke upstream -> convert back.
 */
@Service
public class ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);

    private final OpenAIToLlmConverter inboundConverter;
    private final LlmToOpenAIConverter outboundConverter;
    private final LlmToOpenAIStreamConverter streamConverter;
    private final ModelRouter router;
    private final ProviderRegistry providerRegistry;
    private final StreamingResponseWriter streamingWriter;

    public ChatCompletionService(OpenAIToLlmConverter inboundConverter,
                                 LlmToOpenAIConverter outboundConverter,
                                 LlmToOpenAIStreamConverter streamConverter,
                                 ModelRouter router,
                                 ProviderRegistry providerRegistry,
                                 StreamingResponseWriter streamingWriter) {
        this.inboundConverter = inboundConverter;
        this.outboundConverter = outboundConverter;
        this.streamConverter = streamConverter;
        this.router = router;
        this.providerRegistry = providerRegistry;
        this.streamingWriter = streamingWriter;
    }

    public OpenAIChatResponse handle(OpenAIChatRequest request) {
        LlmRequest llmRequest = inboundConverter.toLlmRequest(request);
        Endpoint endpoint = router.route(llmRequest.getModel());
        LlmProvider provider = providerRegistry.get(endpoint.getProvider());

        log.info("Routing model={} to provider={}", llmRequest.getModel(), endpoint.getProvider());

        LlmResponse llmResponse = provider.invoke(llmRequest, endpoint);

        log.info("Upstream responded in model={}, tokens={}",
                llmResponse.getModel(),
                llmResponse.getUsage() == null ? "n/a" : llmResponse.getUsage().getTotalTokens());

        return outboundConverter.toOpenAIResponse(llmResponse);
    }

    /**
     * Handles a streaming request end to end.
     *
     * <p>Pre-flight steps (conversion, routing, provider lookup) may throw
     * and are handled by {@code GlobalExceptionHandler} because the response
     * has not been committed yet. Once SSE headers are written, errors are
     * propagated as {@link IOException} and the container closes the connection.
     */
    public void handleStream(OpenAIChatRequest request, HttpServletResponse response)
            throws IOException {
        LlmRequest llmRequest = inboundConverter.toLlmRequest(request);
        Endpoint endpoint = router.route(llmRequest.getModel());
        StreamingLlmProvider provider = providerRegistry.getStreaming(endpoint.getProvider());

        log.info("Streaming model={} via provider={}", llmRequest.getModel(), endpoint.getProvider());

        LlmStream stream = null;
        boolean responseCommitted = false;
        try {
            stream = provider.invokeStream(llmRequest, endpoint);
            streamingWriter.prepare(response);
            responseCommitted = true;

            LlmStreamChunk chunk;
            while ((chunk = stream.next()) != null) {
                try {
                    streamingWriter.writeData(response, streamConverter.toSseData(chunk));
                } catch (IOException e) {
                    // Downstream (client) disconnected: stop reading upstream
                    // and release the upstream connection.
                    log.info("Client disconnected while streaming model={}, cancelling upstream",
                            llmRequest.getModel());
                    throw e;
                }
            }
            streamingWriter.writeDone(response);
            log.info("Stream completed for model={}", llmRequest.getModel());

        } catch (IOException e) {
            if (responseCommitted) {
                // Upstream failed after SSE headers were sent.
                // Tell the client the stream is broken, then close.
                log.warn("Upstream stream failed mid-flight for model={}: {}",
                        llmRequest.getModel(), e.getMessage());
                tryWriteErrorEvent(response, e);
            }
            throw e;

        } finally {
            if (stream != null) {
                stream.close();
            }
        }
    }

    private void tryWriteErrorEvent(HttpServletResponse response, IOException cause) {
        try {
            streamingWriter.writeError(response, "upstream_stream_error", cause.getMessage());
        } catch (IOException writeFailure) {
            // Client is already gone; nothing more to do.
            log.debug("Failed to write error event to client: {}", writeFailure.getMessage());
        }
    }
}