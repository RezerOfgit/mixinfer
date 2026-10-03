package com.mixinfer.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects requests to /v1/** that do not carry a valid API key.
 * Writes an OpenAI-compatible error body when rejecting.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_PATH_PREFIX = "/v1/";

    private static final String UNAUTHORIZED_BODY =
            "{\"error\":{\"message\":\"Invalid or missing API key\","
                    + "\"type\":\"authentication_error\",\"code\":\"invalid_api_key\"}}";

    private final ApiKeyValidator validator;

    public ApiKeyFilter(ApiKeyValidator validator) {
        this.validator = validator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(API_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = extractApiKey(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!validator.isValid(apiKey)) {
            writeUnauthorized(response);
            return;
        }

        chain.doFilter(request, response);
    }

    private String extractApiKey(String authHeader) {
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authHeader.substring(BEARER_PREFIX.length()).trim();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}