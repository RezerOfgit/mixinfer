package com.mixinfer.service;

import com.mixinfer.converter.LlmToOpenAIConverter;
import com.mixinfer.converter.LlmToOpenAIStreamConverter;
import com.mixinfer.converter.OpenAIToLlmConverter;
import com.mixinfer.domain.*;
import com.mixinfer.metering.UsageRecorder;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIChatResponse;
import com.mixinfer.provider.LlmProvider;
import com.mixinfer.provider.LlmStream;
import com.mixinfer.provider.ProviderRegistry;
import com.mixinfer.provider.StreamingLlmProvider;
import com.mixinfer.router.Endpoint;
import com.mixinfer.router.ModelRouter;
import com.mixinfer.streaming.StreamingResponseWriter;
import com.mixinfer.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;

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
    private final UsageRecorder usageRecorder;

    public ChatCompletionService(OpenAIToLlmConverter inboundConverter,
                                 LlmToOpenAIConverter outboundConverter,
                                 LlmToOpenAIStreamConverter streamConverter,
                                 ModelRouter router,
                                 ProviderRegistry providerRegistry,
                                 StreamingResponseWriter streamingWriter,
                                 UsageRecorder usageRecorder) {
        this.inboundConverter = inboundConverter;
        this.outboundConverter = outboundConverter;
        this.streamConverter = streamConverter;
        this.router = router;
        this.providerRegistry = providerRegistry;
        this.streamingWriter = streamingWriter;
        this.usageRecorder = usageRecorder;
    }

    public OpenAIChatResponse handle(OpenAIChatRequest request) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        Instant startedAt = Instant.now();
        LlmRequest llmRequest = inboundConverter.toLlmRequest(request);
        Endpoint endpoint = router.route(llmRequest.getModel());
        LlmProvider provider = providerRegistry.get(endpoint.getProvider());

        log.info("Routing model={} to provider={}", llmRequest.getModel(), endpoint.getProvider());

        LlmResponse llmResponse = provider.invoke(llmRequest, endpoint);

        usageRecorder.record(UsageRecord.builder()
                .requestId(requestId)
                .model(llmResponse.getModel())
                .provider(endpoint.getProvider())
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .providerReported(llmResponse.getUsage())
                .settlementStatus(UsageSettlementStatus.FINAL)
                .usageSource(llmResponse.getUsage() != null ? UsageSource.PROVIDER : UsageSource.NONE)
                .build());

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
        Instant startedAt = Instant.now();
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        String model = request.getModel();

        LlmRequest llmRequest = inboundConverter.toLlmRequest(request);
        Endpoint endpoint = router.route(llmRequest.getModel());
        StreamingLlmProvider provider = providerRegistry.getStreaming(endpoint.getProvider());

        log.info("Streaming model={} provider={}", model, endpoint.getProvider());

        LlmStream stream = null;
        boolean responseCommitted = false;
        LlmUsage lastUsage = null;
        UsageSettlementStatus settlement = UsageSettlementStatus.PENDING;
        String reason = null;

        try {
            stream = provider.invokeStream(llmRequest, endpoint);
            streamingWriter.prepare(response);
            responseCommitted = true;

            LlmStreamChunk chunk;
            while ((chunk = stream.next()) != null) {
                if (chunk.getUsage() != null) {
                    lastUsage = chunk.getUsage();
                }
                try {
                    streamingWriter.writeData(response, streamConverter.toSseData(chunk));
                } catch (IOException e) {
                    log.info("Client disconnected while streaming, cancelling upstream");
                    settlement = UsageSettlementStatus.UNKNOWN;
                    reason = "client_disconnected";
                    throw e;
                }
            }
            streamingWriter.writeDone(response);

            settlement = lastUsage != null
                    ? UsageSettlementStatus.FINAL
                    : UsageSettlementStatus.UNKNOWN;
            log.info("Stream completed model={} tokens={}",
                    model,
                    lastUsage == null ? "n/a" : lastUsage.getTotalTokens());

        } catch (IOException e) {
            if (responseCommitted) {
                log.warn("Upstream stream failed mid-flight: {}", e.getMessage());
                if (reason == null) {
                    settlement = lastUsage != null
                            ? UsageSettlementStatus.PARTIAL
                            : UsageSettlementStatus.UNKNOWN;
                    reason = "upstream_stream_error";
                }
                tryWriteErrorEvent(response, e);
            } else {
                settlement = UsageSettlementStatus.UNKNOWN;
                reason = "upstream_open_failed";
            }
            throw e;

        } finally {
            if (stream != null) {
                stream.close();
            }
            try {
                usageRecorder.record(UsageRecord.builder()
                        .requestId(requestId)
                        .model(model)
                        .provider(endpoint.getProvider())
                        .startedAt(startedAt)
                        .completedAt(Instant.now())
                        .providerReported(lastUsage)
                        .settlementStatus(settlement)
                        .usageSource(lastUsage != null ? UsageSource.PROVIDER : UsageSource.NONE)
                        .reason(reason)
                        .build());
            } catch (RuntimeException e) {
                log.warn("Usage settlement failed: {}", e.getMessage());
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