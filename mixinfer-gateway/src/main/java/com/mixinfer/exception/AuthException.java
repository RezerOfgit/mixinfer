package com.mixinfer.exception;

/**
 * Thrown when the client API key is missing or invalid.
 */
public class AuthException extends MixInferException {

    public AuthException(String message) {
        super(message);
    }
}
