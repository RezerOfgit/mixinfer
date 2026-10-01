package com.mixinfer.domain;

import lombok.Builder;
import lombok.Value;

/**
 * A provider-agnostic error representation.
 * Used to normalize upstream errors into a unified format.
 */
@Value
@Builder
public class LlmError {

    /** Machine-readable error code, e.g. "invalid_api_key". */
    String code;

    /** Human-readable error message. */
    String message;

    /** Error category, e.g. "authentication_error", "rate_limit_error". */
    String type;
}
