package com.mixinfer.domain;

/**
 * Describes how confident MixInfer is about a stream's final usage numbers.
 *
 * <p>Streaming usage is inherently uncertain: upstreams may not report it,
 * and disconnections leave the true value unknown. This enum is an explicit
 * settlement state, not a boolean.
 */
public enum UsageSettlementStatus {

    /** The stream is still in progress. */
    PENDING,

    /** The upstream reported complete usage. */
    FINAL,

    /** Only partial usage information is available. */
    PARTIAL,

    /** Usage cannot be determined. */
    UNKNOWN
}