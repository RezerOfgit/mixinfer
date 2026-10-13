package com.mixinfer.auth;

import com.mixinfer.ratelimit.RateLimitPolicy;
import lombok.Builder;
import lombok.Value;

/**
 * A client-facing virtual key: the governance subject for V0.4.
 *
 * <p>{@code id} is stable across name changes and key rotation, and is what
 * appears in usage records. The plaintext {@code key} is used for
 * authentication only and must not be stored in usage records or logged.
 */
@Value
@Builder
public class VirtualKey {

    /** Stable internal identifier. Not a secret. */
    String id;

    /** Display name. */
    String name;

    /** Authentication secret presented by clients. Never logged. */
    String key;

    /** Rate limit policy. May be null (no limit). */
    RateLimitPolicy rateLimit;
}