package com.mixinfer.service;

import com.mixinfer.converter.LlmToOpenAIConverter;
import com.mixinfer.converter.LlmToOpenAIStreamConverter;
import com.mixinfer.converter.OpenAIToLlmConverter;
import com.mixinfer.domain.*;
import com.mixinfer.exception.ProviderException;
import com.mixinfer.metering.UsageRecorder;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIChatResponse;
import com.mixinfer.provider.LlmProvider;
import com.mixinfer.provider.LlmStream;
import com.mixinfer.provider.ProviderRegistry;
import com.mixinfer.provider.StreamingLlmProvider;
import com.mixinfer.router.Endpoint;
import com.mixinfer.router.EndpointSelector;
import com.mixinfer.router.ModelRouter;
import com.mixinfer.router.RouteTarget;
import com.mixinfer.router.failure.FailureClassifier;
import com.mixinfer.router.failure.FailureType;
import com.mixinfer.router.health.EndpointHealthTracker;
import com.mixinfer.streaming.StreamingResponseWriter;
import com.mixinfer.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

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
    private final EndpointHealthTracker healthTracker;
    private final EndpointSelector selector;
    private final FailureClassifier failureClassifier;

    public ChatCompletionService(OpenAIToLlmConverter inboundConverter,
                                 LlmToOpenAIConverter outboundConverter,
                                 LlmToOpenAIStreamConverter streamConverter,
                                 ModelRouter router,
                                 ProviderRegistry providerRegistry,
                                 StreamingResponseWriter streamingWriter,
                                 UsageRecorder usageRecorder,
                                 EndpointHealthTracker healthTracker,
                                 EndpointSelector selector,
                                 FailureClassifier failureClassifier) {
        this.inboundConverter = inboundConverter;
        this.outboundConverter = outboundConverter;
        this.streamConverter = streamConverter;
        this.router = router;
        this.providerRegistry = providerRegistry;
        this.streamingWriter = streamingWriter;
        this.usageRecorder = usageRecorder;
        this.healthTracker = healthTracker;
        this.selector = selector;
        this.failureClassifier = failureClassifier;
    }

    public OpenAIChatResponse handle(OpenAIChatRequest request) {
        Instant startedAt = Instant.now();
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        String model = request.getModel();

        LlmRequest llmRequest = inboundConverter.toLlmRequest(request);

        List<RouteTarget> candidates = router.route(llmRequest.getModel());
        List<RouteTarget> healthy = healthTracker.filter(candidates);
        List<RouteTarget> ordered = selector.order(healthy);

        ProviderException lastError = null;
        String lastProvider = null;

        for (int i = 0; i < ordered.size(); i++) {
            Endpoint endpoint = ordered.get(i).getEndpoint();
            lastProvider = endpoint.getProvider();
            boolean isLastAttempt = (i == ordered.size() - 1);

            try {
                log.info("Routing model={} to provider={} (attempt {}/{})",
                        model, endpoint.getProvider(), i + 1, ordered.size());

                LlmProvider provider = providerRegistry.get(endpoint.getProvider());
                LlmResponse llmResponse = provider.invoke(llmRequest, endpoint);

                healthTracker.recordSuccess(endpoint);

                log.info("Upstream responded model={}, tokens={}",
                        llmResponse.getModel(),
                        llmResponse.getUsage() == null
                                ? "n/a" : llmResponse.getUsage().getTotalTokens());

                usageRecorder.record(UsageRecord.builder()
                        .requestId(requestId)
                        .model(llmResponse.getModel())
                        .provider(endpoint.getProvider())
                        .startedAt(startedAt)
                        .completedAt(Instant.now())
                        .providerReported(llmResponse.getUsage())
                        .settlementStatus(UsageSettlementStatus.FINAL)
                        .usageSource(llmResponse.getUsage() != null
                                ? UsageSource.PROVIDER : UsageSource.NONE)
                        .build());

                return outboundConverter.toOpenAIResponse(llmResponse);

            } catch (ProviderException e) {
                FailureType failureType = failureClassifier.classify(e);
                healthTracker.recordFailure(endpoint);

                log.warn("Provider {} failed (type={}, attempt {}/{}): {}",
                        endpoint.getProvider(), failureType, i + 1, ordered.size(),
                        e.getMessage());

                if (!failureType.isFailoverable()) {
                    log.info("Failure type {} is not failoverable; aborting",
                            failureType);
                    throw e;
                }

                lastError = e;
                if (isLastAttempt) {
                    break;
                }
            }
        }

        log.warn("All {} endpoints failed for model={}", ordered.size(), model);

        usageRecorder.record(UsageRecord.builder()
                .requestId(requestId)
                .model(model)
                .provider(lastProvider)
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .settlementStatus(UsageSettlementStatus.UNKNOWN)
                .usageSource(UsageSource.NONE)
                .reason("all_endpoints_failed")
                .build());

        throw new ProviderException(
                "All " + ordered.size() + " endpoints failed for model " + model,
                lastError);
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
        Endpoint endpoint = router.route(llmRequest.getModel()).get(0).getEndpoint();
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