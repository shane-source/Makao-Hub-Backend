package com.makaohub.backend.auth.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;

public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, RateLimit> RATE_LIMITS = Map.of(
            "/api/v1/auth/login",
            new RateLimit(10, Duration.ofMinutes(1)),

            "/api/v1/auth/register",
            new RateLimit(5, Duration.ofHours(1)),

            "/api/v1/auth/google",
            new RateLimit(10, Duration.ofMinutes(1)),

            "/api/v1/auth/google/register",
            new RateLimit(5, Duration.ofHours(1)),

            "/api/v1/auth/refresh",
            new RateLimit(30, Duration.ofMinutes(1)),

            "/api/v1/auth/logout",
            new RateLimit(30, Duration.ofMinutes(1))
    );

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !RATE_LIMITS.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimit rateLimit = RATE_LIMITS.get(
                request.getRequestURI()
        );

        String bucketKey = request.getRemoteAddr()
                + "|"
                + request.getRequestURI();

        Bucket bucket = buckets.get(
                bucketKey,
                ignored -> createBucket(rateLimit)
        );

        ConsumptionProbe probe =
                bucket.tryConsumeAndReturnRemaining(1);

        response.setHeader(
                "X-RateLimit-Remaining",
                Long.toString(probe.getRemainingTokens())
        );

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        writeRateLimitResponse(response, probe);
    }

    private Bucket createBucket(RateLimit rateLimit) {
        return Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(rateLimit.capacity())
                        .refillGreedy(
                                rateLimit.capacity(),
                                rateLimit.refillPeriod()
                        )
                )
                .build();
    }

    private void writeRateLimitResponse(
            HttpServletResponse response,
            ConsumptionProbe probe
    ) throws IOException {
        long waitNanos = probe.getNanosToWaitForRefill();

        long retryAfterSeconds =
                TimeUnit.NANOSECONDS.toSeconds(waitNanos);

        if (waitNanos % 1_000_000_000L != 0) {
            retryAfterSeconds++;
        }

        response.setStatus(
                HttpStatus.TOO_MANY_REQUESTS.value()
        );

        response.setHeader(
                "Retry-After",
                Long.toString(Math.max(1, retryAfterSeconds))
        );

        response.setContentType(
                MediaType.APPLICATION_PROBLEM_JSON_VALUE
        );

        response.setCharacterEncoding(
                StandardCharsets.UTF_8.name()
        );

        response.getWriter().write("""
                {
                  "title": "Too many requests",
                  "status": 429,
                  "detail": "Too many authentication requests. Try again later.",
                  "code": "RATE_LIMIT_EXCEEDED"
                }
                """);
    }

    private record RateLimit(
            long capacity,
            Duration refillPeriod
    ) {
    }
}