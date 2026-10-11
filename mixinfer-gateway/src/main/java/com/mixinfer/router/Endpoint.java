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

    /**
     * Request timeout.
     *
     * <p>For non-streaming calls: total time from sending the request
     * to receiving the full response body.
     *
     * <p>For streaming calls: covers only the time until upstream
     * response headers arrive. It does NOT bound the total stream
     * lifetime; JDK {@code HttpRequest.timeout()} stops applying once
     * the body stream is handed to the caller. A stream-lifetime
     * timeout is deferred to V0.3.1.
     */
    Duration requestTimeout;

    /** Idle timeout. Used by streaming clients only. */
//    Duration idleTimeout;
}