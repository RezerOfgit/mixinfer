package com.mixinfer.router;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

/**
 * A resolved endpoint: a provider plus the connection details
 * needed to invoke it, including per-endpoint timeouts.
 */
@Value
@Builder
public class Endpoint {

    String provider;
    String baseUrl;
    String apiKey;

    /** Connect timeout. Used by both streaming and non-streaming clients. */
    Duration connectTimeout;

    /** Request timeout. Used by non-streaming clients only. */
    /** Non-streaming: full request. Streaming: total stream lifetime. */
    Duration requestTimeout;

    /** Idle timeout. Used by streaming clients only. */
//    Duration idleTimeout;
}