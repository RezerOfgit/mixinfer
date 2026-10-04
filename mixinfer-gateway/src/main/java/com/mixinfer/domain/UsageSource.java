package com.mixinfer.domain;

/**
 * Describes where usage numbers came from.
 */
public enum UsageSource {

    /** Reported by the upstream provider. */
    PROVIDER,

    /** Estimated locally, e.g. by a tokenizer. Reserved for V0.3. */
    ESTIMATED,

    /** No usage information available. */
    NONE
}