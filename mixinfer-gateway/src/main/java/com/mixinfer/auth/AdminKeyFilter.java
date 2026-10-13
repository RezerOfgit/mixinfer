package com.mixinfer.auth;

import com.mixinfer.config.MixInferProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Rejects requests to /admin/** that do not carry the configured admin key.
 *
 * <p>Admin authentication is intentionally separate from client virtual keys
 * (see ADR-004 decision 8): a client key must never grant access to global
 * usage data, and the admin key must not grant access to /v1/**.
 *
 * <p>When no admin key is configured, the /admin/** endpoints are disabled
 * and return 404 rather than 401, so that the absence of the feature is
 * distinguishable from a missing credential.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminKeyFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_PATH_PREFIX = "/admin/";

    private final MixInferProperties properties;

    public AdminKeyFilter(MixInferProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(ADMIN_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String configured = properties.getAdmin().getApiKey();
        if (configured == null || configured.isBlank()) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "admin_disabled", "Admin API is not configured");
            return;
        }

        String presented = extractBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!constantTimeEquals(presented, configured)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "invalid_admin_key", "Invalid or missing admin API key");
            return;
        }

        chain.doFilter(request, response);
    }

    private String extractBearer(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * Constant-time comparison to avoid leaking the admin key via timing.
     * Returns false for any null input.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, int status,
                            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String body = "{\"error\":{\"message\":\"" + message
                + "\",\"type\":\"admin_error\",\"code\":\"" + code + "\"}}";
        response.getWriter().write(body);
    }
}