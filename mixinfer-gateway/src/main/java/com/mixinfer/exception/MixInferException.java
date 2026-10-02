package com.mixinfer.exception;

/**
 * Base class for all MixInfer business exceptions.
 */
public class MixInferException extends RuntimeException {

    public MixInferException(String message) {
        super(message);
    }

    public MixInferException(String message, Throwable cause) {
        super(message, cause);
    }
}