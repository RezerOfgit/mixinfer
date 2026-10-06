package com.mixinfer.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a unique requestId to every incoming request.
 *
 * <p>The id is:
 * <ul>
 *   <li>placed in the SLF4J {@link MDC} under the key {@code requestId},
 *       so all log lines for this request carry it automatically;</li>
 *   <li>returned in the {@code X-MixInfer-Request-Id} response header,
 *       so clients can correlate logs and support tickets.</li>
 * </ul>
 *
 * <p>The id is always generated server-side; client-supplied values are
 * ignored to prevent log injection and trace forgery.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER_NAME = "X-MixInfer-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Prevent MDC leakage into the next request handled by this thread.
            MDC.remove(MDC_KEY);
        }
    }
}