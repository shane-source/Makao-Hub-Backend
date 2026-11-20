package com.makaohub.backend.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Logs every failed API call: method, path, status, duration and a request id (also returned in the
 * X-Request-Id header so a phone error can be matched to a server line). Never logs bodies, headers
 * other than the id, or query strings.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiErrorLogging extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("makao.api");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/") || path.startsWith("/payments/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("requestId", id);
        response.setHeader("X-Request-Id", id);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            log.error("API_EXCEPTION id={} {} {} -> {}", id, request.getMethod(), request.getRequestURI(),
                    exception.toString(), exception);
            throw exception;
        } finally {
            int status = response.getStatus();
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (status >= 500) {
                log.error("API_5XX id={} {} {} status={} {}ms", id, request.getMethod(), request.getRequestURI(), status, ms);
            } else if (status >= 400) {
                log.warn("API_4XX id={} {} {} status={} {}ms", id, request.getMethod(), request.getRequestURI(), status, ms);
            }
            MDC.remove("requestId");
        }
    }
}
