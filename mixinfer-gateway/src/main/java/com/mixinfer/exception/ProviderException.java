package com.mixinfer.exception;

/**
 * Thrown when a provider call fails: network error, timeout,
 * or an error status returned by the upstream LLM.
 */
public class ProviderException extends MixInferException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
