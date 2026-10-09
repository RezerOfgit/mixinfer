package com.mixinfer.router.failure;

/**
 * Classification of an upstream failure for routing decisions.
 *
 * <p>Classification is by failure semantics, not by Java exception type.
 * Only {@link #isFailoverable()} failure types trigger a failover to the
 * next endpoint; the rest are surfaced to the client unchanged.
 */
public enum FailureType {

    // -- Failoverable --
    /** Connection refused, DNS failure, TLS handshake failure. */
    CONNECTION_FAILURE(true),
    /** TCP/TLS connection did not complete in time. */
    CONNECT_TIMEOUT(true),
    /** Connection established, but the response did not arrive in time. */
    READ_TIMEOUT(true),
    /** Upstream returned HTTP 408 Request Timeout. */
    HTTP_408(true),
    /** Upstream returned HTTP 5xx. */
    HTTP_5XX(true),
    /** Upstream returned HTTP 429 Too Many Requests. */
    HTTP_429(true),

    // -- Not failoverable --
    /** Upstream returned HTTP 401 or 403. */
    AUTHENTICATION_FAILURE(false),
    /** Upstream returned HTTP 400. */
    INVALID_REQUEST(false),
    /** Upstream returned HTTP 404. */
    MODEL_NOT_FOUND(false),

    // -- Ambiguous --
    /**
     * The failure type cannot be determined. Treated as not failoverable,
     * because the upstream may have executed the request. Conservative by
     * design (see ADR-003 Decision 5).
     */
    UNKNOWN(false);

    private final boolean failoverable;

    FailureType(boolean failoverable) {
        this.failoverable = failoverable;
    }

    public boolean isFailoverable() {
        return failoverable;
    }
}