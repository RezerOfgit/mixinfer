package com.mixinfer.exception;

/**
 * Thrown when no route is configured for the requested model.
 */
public class RoutingException extends MixInferException {

    public RoutingException(String message) {
        super(message);
    }
}
