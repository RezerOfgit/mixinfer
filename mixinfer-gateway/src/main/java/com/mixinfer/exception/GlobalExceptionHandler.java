package com.mixinfer.exception;

import com.mixinfer.openai.OpenAIErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps MixInfer business exceptions to OpenAI-compatible error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<OpenAIErrorResponse> handleAuth(AuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(OpenAIErrorResponse.of(e.getMessage(),
                        "authentication_error", "invalid_api_key"));
    }

    @ExceptionHandler(RoutingException.class)
    public ResponseEntity<OpenAIErrorResponse> handleRouting(RoutingException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(OpenAIErrorResponse.of(e.getMessage(),
                        "invalid_request_error", "model_not_found"));
    }

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<OpenAIErrorResponse> handleProvider(ProviderException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(OpenAIErrorResponse.of(e.getMessage(),
                        "upstream_error", "provider_error"));
    }

    @ExceptionHandler(MixInferException.class)
    public ResponseEntity<OpenAIErrorResponse> handleGeneric(MixInferException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(OpenAIErrorResponse.of(e.getMessage(),
                        "internal_error", "internal_error"));
    }
}