package com.mixinfer.router;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.util.Map;

/**
 * A resolved endpoint: a provider plus the connection details needed
 * to invoke it, including per-endpoint timeouts.
 *
 * <p>{@code id} is a stable identifier used in usage records.
 *
 * <p>{@code pricing} maps upstream model identifiers to their pricing
 * tables. A missing entry means "no pricing configured for this model";
 * costs for such calls are recorded as null, not zero.
 */
@Value
@Builder
public class Endpoint {

    String id;
    String provider;
    String baseUrl;
    String apiKey;

    Duration connectTimeout;
    Duration requestTimeout;

    /** Upstream model name to pricing. May be empty. */
    Map<String, Pricing> pricing;
}