package com.mixinfer.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OpenAI-compatible error response.
 * Shape: {"error": {"message": "...", "type": "...", "code": "..."}}
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIErrorResponse {

    private ErrorDetail error;

    public static OpenAIErrorResponse of(String message, String type, String code) {
        ErrorDetail detail = new ErrorDetail(message, type, code);
        OpenAIErrorResponse response = new OpenAIErrorResponse();
        response.setError(detail);
        return response;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {
        private String message;
        private String type;
        private String code;
    }
}