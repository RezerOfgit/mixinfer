package com.mixinfer.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Rejects requests to /v1/** that do not carry a valid virtual key.
 *
 * <p>On success, the resolved {@link VirtualKey#getId()} is placed in the
 * SLF4J MDC under {@code virtualKeyId} so that downstream components can
 * attribute usage without re-resolving the secret.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "virtualKeyId";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_PATH_PREFIX = "/v1/";

    private static final String UNAUTHORIZED_BODY =
            "{\"error\":{\"message\":\"Invalid or missing API key\","
                    + "\"type\":\"authentication_error\",\"code\":\"invalid_api_key\"}}";

    private final VirtualKeyRegistry registry;

    public ApiKeyFilter(VirtualKeyRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(API_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String secret = extractBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        Optional<VirtualKey> key = registry.findBySecret(secret);
        if (key.isEmpty()) {
            writeUnauthorized(response);
            return;
        }

        MDC.put(MDC_KEY, key.get().getId());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String extractBearer(String authHeader) {
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