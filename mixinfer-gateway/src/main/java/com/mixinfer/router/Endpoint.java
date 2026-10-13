package com.mixinfer.router;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

/**
 * A resolved endpoint: a provider plus the connection details needed
 * to invoke it, including per-endpoint timeouts.
 *
 * <p>{@code id} is a stable identifier used in usage records. In V0.4
 * it is derived from the provider name at configuration load time.
 * Future versions may introduce a distinct endpoint identity when a
 * single provider can expose multiple endpoints.
 */
@Value
@Builder
public class Endpoint {

    /** Stable identifier for usage attribution. */
    String id;

    /** Logical provider name. */
    String provider;

    String baseUrl;
    String apiKey;

    Duration connectTimeout;
    Duration requestTimeout;
}