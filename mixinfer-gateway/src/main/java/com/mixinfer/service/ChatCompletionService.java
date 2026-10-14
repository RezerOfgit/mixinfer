package com.mixinfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.auth.ApiKeyFilter;
import com.mixinfer.auth.VirtualKey;
import com.mixinfer.auth.VirtualKeyRegistry;
import com.mixinfer.converter.LlmToOpenAIConverter;
import com.mixinfer.converter.LlmToOpenAIStreamConverter;
import com.mixinfer.converter.OpenAIToLlmConverter;
import com.mixinfer.domain.*;
import com.mixinfer.exception.ClientDisconnectedException;
import com.mixinfer.exception.ProviderException;
import com.mixinfer.exception.RateLimitExceededException;
import com.mixinfer.metering.AttemptLog;
import com.mixinfer.metering.CostCalculator;
import com.mixinfer.metering.CostResult;
import com.mixinfer.metering.UsageRecorder;
import com.mixinfer.openai.OpenAIChatRequest;
import com.mixinfer.openai.OpenAIChatResponse;
import com.mixinfer.provider.LlmProvider;
import com.mixinfer.provider.LlmStream;
import com.mixinfer.provider.ProviderRegistry;
import com.mixinfer.provider.StreamingLlmProvider;
import com.mixinfer.ratelimit.RateLimiter;
import com.mixinfer.router.Endpoint;
import com.mixinfer.router.Pricing;
import com.mixinfer.router.RoutePlanner;
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
    private final RoutePlanner routePlanner;
    private final ProviderRegistry providerRegistry;
    private final StreamingResponseWriter streamingWriter;
    private final UsageRecorder usageRecorder;
    private final EndpointHealthTracker healthTracker;
    private final FailureClassifier failureClassifier;
    private final ObjectMapper objectMapper;
    private final CostCalculator costCalculator;
    private final RateLimiter rateLimiter;
    private final VirtualKeyRegistry virtualKeyRegistry;

    public ChatCompletionService(OpenAIToLlmConverter inboundConverter,
                                 LlmToOpenAIConverter outboundConverter,
                                 LlmToOpenAIStreamConverter streamConverter,
                                 RoutePlanner routePlanner,
                                 ProviderRegistry providerRegistry,
                                 StreamingResponseWriter streamingWriter,
                                 UsageRecorder usageRecorder,
                                 EndpointHealthTracker healthTracker,
                                 FailureClassifier failureClassifier,
                                 ObjectMapper objectMapper,
                                 CostCalculator costCalculator,
                                 RateLimiter rateLimiter,
                                 VirtualKeyRegistry virtualKeyRegistry) {
        this.inboundConverter = inboundConverter;
        this.outboundConverter = outboundConverter;
        this.streamConverter = streamConverter;
        this.routePlanner = routePlanner;
        this.providerRegistry = providerRegistry;
        this.streamingWriter = streamingWriter;
        this.usageRecorder = usageRecorder;
        this.healthTracker = healthTracker;
        this.failureClassifier = failureClassifier;
        this.objectMapper = objectMapper;
        this.costCalculator = costCalculator;
        this.rateLimiter = rateLimiter;
        this.virtualKeyRegistry = virtualKeyRegistry;
    }

    public OpenAIChatResponse handle(OpenAIChatRequest request) {
        Instant startedAt = Instant.now();
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        String virtualKeyId = MDC.get(ApiKeyFilter.MDC_KEY);
        String model = request.getModel();

        enforceRateLimit(requestId, virtualKeyId, model, startedAt);

        LlmRequest llmRequest = inboundConverter.toLlmRequest(request);

        List<RouteTarget> ordered;
        try {
            ordered = routePlanner.plan(llmRequest.getModel());
        } catch (RuntimeException e) {
            recordFailure(requestId, virtualKeyId, model, startedAt,
                    "routing_failed", null);
            throw e;
        }

        AttemptLog attempts = new AttemptLog();
        ProviderException lastError = null;
        Endpoint lastEndpoint = null;

        for (int i = 0; i < ordered.size(); i++) {
            Endpoint endpoint = ordered.get(i).getEndpoint();
            lastEndpoint = endpoint;
            boolean isLastAttempt = (i == ordered.size() - 1);

            try {
                log.info("Routing model={} to provider={} (attempt {}/{})",
                        model, endpoint.getProvider(), i + 1, ordered.size());

                LlmProvider provider = providerRegistry.get(endpoint.getProvider());
                LlmResponse llmResponse = provider.invoke(llmRequest, endpoint);

                healthTracker.recordSuccess(endpoint);
                attempts.recordSuccess(endpoint.getProvider(), endpoint.getId());

                CostResult cost = computeCost(endpoint, llmResponse);

                log.info("Upstream responded model={} upstreamModel={} tokens={} costMicrocents={}",
                        model, llmResponse.getModel(),
                        llmResponse.getUsage() == null
                                ? "n/a" : llmResponse.getUsage().getTotalTokens(),
                        cost == null ? "n/a" : cost.microcents());

                usageRecorder.record(UsageRecord.builder()
                        .requestId(requestId)
                        .virtualKeyId(virtualKeyId)
                        .model(model)
                        .provider(endpoint.getProvider())
                        .endpointId(endpoint.getId())
                        .upstreamModel(llmResponse.getModel())
                        .startedAt(startedAt)
                        .completedAt(Instant.now())
                        .providerReported(llmResponse.getUsage())
                        .costMicrocents(cost == null ? null : cost.microcents())
                        .pricingVersion(cost == null ? null : cost.pricingVersion())
                        .settlementStatus(UsageSettlementStatus.FINAL)
                        .usageSource(llmResponse.getUsage() != null
                                ? UsageSource.PROVIDER : UsageSource.NONE)
                        .success(true)
                        .attemptCount(attempts.size())
                        .attempts(attempts.toJson(objectMapper))
                        .build());

                return outboundConverter.toOpenAIResponse(llmResponse);

            } catch (ProviderException e) {
                FailureType failureType = failureClassifier.classify(e);

                if (!failureType.isFailoverable()) {
                    log.warn("Provider {} failed with non-failoverable type={}: {}",
                            endpoint.getProvider(), failureType, e.getMessage());
                    attempts.recordFailure(endpoint.getProvider(), endpoint.getId(),
                            failureType.name(), e.getMessage());
                    recordFailure(requestId, virtualKeyId, model, startedAt,
                            "non_failoverable_provider_error", attempts);
                    throw e;
                }

                // Only failoverable failures count against endpoint health.
                // Auth, invalid request, and model-not-found errors reflect
                // request configuration, not endpoint availability.
                healthTracker.recordFailure(endpoint);
                attempts.recordFailure(endpoint.getProvider(), endpoint.getId(),
                        failureType.name(), e.getMessage());
                log.warn("Provider {} failed (type={}, attempt {}/{}): {}",
                        endpoint.getProvider(), failureType, i + 1, ordered.size(),
                        e.getMessage());

                lastError = e;
                if (isLastAttempt) {
                    break;
                }
            }
        }

        log.warn("All {} endpoints failed for model={}", ordered.size(), model);

        usageRecorder.record(UsageRecord.builder()
                .requestId(requestId)
                .virtualKeyId(virtualKeyId)
                .model(model)
                .provider(lastEndpoint == null ? null : lastEndpoint.getProvider())
                .endpointId(lastEndpoint == null ? null : lastEndpoint.getId())
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .settlementStatus(UsageSettlementStatus.UNKNOWN)
                .usageSource(UsageSource.NONE)
                .reason("all_endpoints_failed")
                .success(false)
                .attemptCount(attempts.size())
                .attempts(attempts.toJson(objectMapper))
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
        String virtualKeyId = MDC.get(ApiKeyFilter.MDC_KEY);
        String model = request.getModel();

        enforceRateLimit(requestId, virtualKeyId, model, startedAt);

        LlmRequest llmRequest = inboundConverter.toLlmRequest(request);

        List<RouteTarget> ordered;
        try {
            ordered = routePlanner.plan(llmRequest.getModel());
        } catch (RuntimeException e) {
            recordFailure(requestId, virtualKeyId, model, startedAt,
                    "routing_failed", null);
            throw e;
        }

        AttemptLog attempts = new AttemptLog();

        // Phase 1: open the upstream stream, with failover only before commit.
        LlmStream stream = null;
        Endpoint selected = null;
        ProviderException lastError = null;

        for (int i = 0; i < ordered.size(); i++) {
            Endpoint endpoint = ordered.get(i).getEndpoint();
            boolean isLastAttempt = (i == ordered.size() - 1);

            try {
                log.info("Streaming model={} via provider={} (attempt {}/{})",
                        model, endpoint.getProvider(), i + 1, ordered.size());

                StreamingLlmProvider provider = providerRegistry.getStreaming(endpoint.getProvider());
                stream = provider.invokeStream(llmRequest, endpoint);
                selected = endpoint;
                attempts.recordOpened(endpoint.getProvider(), endpoint.getId());
                break;

            } catch (ProviderException e) {
                FailureType failureType = failureClassifier.classify(e);

                if (!failureType.isFailoverable()) {
                    log.warn("Streaming open failed with non-failoverable type={}: {}",
                            failureType, e.getMessage());
                    attempts.recordFailure(endpoint.getProvider(), endpoint.getId(),
                            failureType.name(), e.getMessage());
                    recordFailure(requestId, virtualKeyId, model, startedAt,
                            "non_failoverable_provider_error", attempts);
                    throw e;
                }

                // Only failoverable failures count against endpoint health.
                healthTracker.recordFailure(endpoint);
                attempts.recordFailure(endpoint.getProvider(), endpoint.getId(),
                        failureType.name(), e.getMessage());
                log.warn("Streaming open failed (type={}, attempt {}/{}): {}",
                        failureType, i + 1, ordered.size(), e.getMessage());

                lastError = e;
                if (isLastAttempt) {
                    break;
                }
            }
        }

        if (stream == null) {
            log.warn("All {} endpoints failed to open for model={}", ordered.size(), model);
            usageRecorder.record(UsageRecord.builder()
                    .requestId(requestId)
                    .virtualKeyId(virtualKeyId)
                    .model(model)
                    .startedAt(startedAt)
                    .completedAt(Instant.now())
                    .settlementStatus(UsageSettlementStatus.UNKNOWN)
                    .usageSource(UsageSource.NONE)
                    .reason("all_endpoints_failed")
                    .success(false)
                    .attemptCount(attempts.size())
                    .attempts(attempts.toJson(objectMapper))
                    .build());
            throw new ProviderException(
                    "All " + ordered.size() + " endpoints failed to open for model " + model,
                    lastError);
        }

        // Phase 2: forward. No failover after this point.
        boolean responseCommitted = false;
        LlmUsage lastUsage = null;
        String upstreamModel = null;
        UsageSettlementStatus settlement = UsageSettlementStatus.PENDING;
        String reason = null;
        boolean success = false;

        try {
            streamingWriter.prepare(response);
            responseCommitted = true;

            LlmStreamChunk chunk;
            while ((chunk = stream.next()) != null) {
                if (chunk.getUsage() != null) {
                    lastUsage = chunk.getUsage();
                }
                if (chunk.getModel() != null) {
                    upstreamModel = chunk.getModel();
                }
                try {
                    streamingWriter.writeData(response, streamConverter.toSseData(chunk));
                } catch (IOException e) {
                    log.info("Client disconnected while streaming, cancelling upstream");
                    settlement = UsageSettlementStatus.UNKNOWN;
                    reason = "client_disconnected";
                    throw new ClientDisconnectedException("client disconnected", e);
                }
            }
            streamingWriter.writeDone(response);

            settlement = lastUsage != null
                    ? UsageSettlementStatus.FINAL
                    : UsageSettlementStatus.UNKNOWN;
            success = true;
            healthTracker.recordSuccess(selected);
            log.info("Stream completed model={} tokens={}",
                    model, lastUsage == null ? "n/a" : lastUsage.getTotalTokens());

        } catch (ClientDisconnectedException e) {
            // Settlement already set inside the loop; do not reclassify
            // as an upstream failure. Endpoint health is not affected
            // because the client abandoned the connection.
            throw e;

        } catch (IOException e) {
            if (responseCommitted) {
                log.warn("Upstream stream failed mid-flight: {}", e.getMessage());
                settlement = lastUsage != null
                        ? UsageSettlementStatus.PARTIAL
                        : UsageSettlementStatus.UNKNOWN;
                reason = "upstream_stream_error";
                healthTracker.recordFailure(selected);
                tryWriteErrorEvent(response, e);
            } else {
                settlement = UsageSettlementStatus.UNKNOWN;
                reason = "upstream_open_failed";
            }
            throw e;

        } finally {
            stream.close();
            try {
                CostResult cost = computeCost(selected, upstreamModel, lastUsage);

                usageRecorder.record(UsageRecord.builder()
                        .requestId(requestId)
                        .virtualKeyId(virtualKeyId)
                        .model(model)
                        .provider(selected.getProvider())
                        .endpointId(selected.getId())
                        .upstreamModel(upstreamModel)
                        .startedAt(startedAt)
                        .completedAt(Instant.now())
                        .providerReported(lastUsage)
                        .costMicrocents(cost == null ? null : cost.microcents())
                        .pricingVersion(cost == null ? null : cost.pricingVersion())
                        .settlementStatus(settlement)
                        .usageSource(lastUsage != null ? UsageSource.PROVIDER : UsageSource.NONE)
                        .reason(reason)
                        .success(success)
                        .attemptCount(attempts.size())
                        .attempts(attempts.toJson(objectMapper))
                        .build());
            } catch (RuntimeException e) {
                log.warn("Usage settlement failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Records a usage record for a request that failed before or during
     * upstream invocation. Does not throw: settlement is best-effort.
     */
    private void recordFailure(String requestId,
                               String virtualKeyId,
                               String model,
                               Instant startedAt,
                               String reason,
                               AttemptLog attempts) {
        usageRecorder.record(UsageRecord.builder()
                .requestId(requestId)
                .virtualKeyId(virtualKeyId)
                .model(model)
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .settlementStatus(UsageSettlementStatus.UNKNOWN)
                .usageSource(UsageSource.NONE)
                .reason(reason)
                .success(false)
                .attemptCount(attempts == null ? 0 : attempts.size())
                .attempts(attempts == null ? null : attempts.toJson(objectMapper))
                .build());
    }

    /**
     * Enforces the rate limit for the request's virtual key.
     *
     * <p>On rejection, records an UNKNOWN usage record with reason
     * {@code rate_limited} and throws {@link RateLimitExceededException}.
     * The record's {@code success} is false; no usage is settled.
     */
    private void enforceRateLimit(String requestId,
                                  String virtualKeyId,
                                  String model,
                                  Instant startedAt) {
        if (virtualKeyId == null) {
            return;
        }
        VirtualKey key = virtualKeyRegistry.findById(virtualKeyId).orElse(null);
        if (key == null || key.getRateLimit() == null) {
            return;
        }

        RateLimiter.Decision decision = rateLimiter.tryAcquire(virtualKeyId, key.getRateLimit());
        if (decision.admitted()) {
            return;
        }

        log.warn("Rate limit exceeded for virtualKeyId={} model={} retryAfter={}ms",
                virtualKeyId, model, decision.retryAfter().toMillis());

        usageRecorder.record(UsageRecord.builder()
                .requestId(requestId)
                .virtualKeyId(virtualKeyId)
                .model(model)
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .settlementStatus(UsageSettlementStatus.UNKNOWN)
                .usageSource(UsageSource.NONE)
                .reason("rate_limited")
                .success(false)
                .attemptCount(0)
                .build());

        throw new RateLimitExceededException(
                "Rate limit exceeded for virtual key", decision.retryAfter());
    }

    private void tryWriteErrorEvent(HttpServletResponse response, IOException cause) {
        try {
            streamingWriter.writeError(response, "upstream_stream_error", cause.getMessage());
        } catch (IOException writeFailure) {
            // Client is already gone; nothing more to do.
            log.debug("Failed to write error event to client: {}", writeFailure.getMessage());
        }
    }

    /** Computes cost from a non-streaming response. */
    private CostResult computeCost(Endpoint endpoint, LlmResponse response) {
        if (endpoint.getPricing() == null || response.getModel() == null) {
            return null;
        }
        Pricing pricing = endpoint.getPricing().get(response.getModel());
        return costCalculator.calculate(response.getUsage(), pricing);
    }

    /** Computes cost from a streaming response. */
    private CostResult computeCost(Endpoint endpoint, String upstreamModel, LlmUsage usage) {
        if (endpoint.getPricing() == null || upstreamModel == null) {
            return null;
        }
        Pricing pricing = endpoint.getPricing().get(upstreamModel);
        return costCalculator.calculate(usage, pricing);
    }
}