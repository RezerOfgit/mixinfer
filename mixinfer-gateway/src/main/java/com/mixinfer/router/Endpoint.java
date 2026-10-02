package com.mixinfer.router;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

/**
 * A resolved endpoint: a provider plus the connection details
 * needed to invoke it.
 */
@Value
@Builder
public class Endpoint {

    /** Name of the provider handling this endpoint. */
    String provider;

    /** Base URL of the upstream API, e.g. https://api.openai.com/v1. */
    String baseUrl;

    /** API key used to authenticate against the upstream. */
    String apiKey;

    /** HTTP timeout for a single call. */
    Duration timeout;
}
