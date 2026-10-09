package com.mixinfer.exception;

/**
 * Thrown when a provider call fails: network error, timeout,
 * or an error status returned by the upstream LLM.
 *
 * <p>When the failure is caused by a non-2xx HTTP response, {@code httpStatus}
 * carries the status code so that the failure can be classified for
 * failover decisions.
 */
public class ProviderException extends MixInferException {

    private final Integer httpStatus;

    public ProviderException(String message) {
        this(message, (Integer) null, null);
    }

    public ProviderException(String message, Throwable cause) {
        this(message, (Integer) null, cause);
    }

    public ProviderException(String message, Integer httpStatus) {
        this(message, httpStatus, null);
    }

    public ProviderException(String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** HTTP status code if the failure came from a non-2xx response; otherwise null. */
    public Integer getHttpStatus() {
        return httpStatus;
    }
}