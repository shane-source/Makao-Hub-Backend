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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, RateLimit> RATE_LIMITS = Map.ofEntries(
            Map.entry("/api/v1/staff/auth/login", new RateLimit(10, Duration.ofMinutes(1))),
            Map.entry("/api/v1/staff/auth/verify-otp", new RateLimit(10, Duration.ofMinutes(15))),
            Map.entry("/api/v1/staff/auth/accept-invitation", new RateLimit(5, Duration.ofHours(1))),
            Map.entry("/api/v1/staff/auth/refresh", new RateLimit(30, Duration.ofMinutes(1))),
            Map.entry("/api/v1/staff/auth/forgot-password", new RateLimit(5, Duration.ofHours(1))),
            Map.entry("/api/v1/staff/auth/reset-password", new RateLimit(10, Duration.ofHours(1))),
            Map.entry("/api/v1/reports", new RateLimit(10, Duration.ofHours(1))),
            // Jirani costs money per call. This only slows bursts; JiraniService keeps its own daily limit per user.
            Map.entry("/api/v1/support/chat", new RateLimit(20, Duration.ofMinutes(10))),
            Map.entry("/api/v1/auth/login", new RateLimit(10, Duration.ofMinutes(1))),

            Map.entry("/api/v1/auth/register", new RateLimit(5, Duration.ofHours(1))),

            Map.entry("/api/v1/auth/register/verify-otp", new RateLimit(10, Duration.ofMinutes(15))),

            Map.entry("/api/v1/auth/google", new RateLimit(10, Duration.ofMinutes(1))),

            Map.entry("/api/v1/auth/login/verify-otp", new RateLimit(10, Duration.ofMinutes(15))),
            Map.entry("/api/v1/auth/reactivate/verify-otp", new RateLimit(10, Duration.ofMinutes(15))),

            Map.entry("/api/v1/auth/google/verify-otp", new RateLimit(10, Duration.ofMinutes(15))),

            Map.entry("/api/v1/auth/forgot-password", new RateLimit(5, Duration.ofHours(1))),

            Map.entry("/api/v1/auth/forgot-password/reset", new RateLimit(10, Duration.ofHours(1))),

            Map.entry("/api/v1/auth/google/register", new RateLimit(5, Duration.ofHours(1))),

            Map.entry("/api/v1/auth/refresh", new RateLimit(30, Duration.ofMinutes(1))),

            Map.entry("/api/v1/auth/logout", new RateLimit(30, Duration.ofMinutes(1))),

            Map.entry("/api/v1/profile/me/phone/complete", new RateLimit(10, Duration.ofMinutes(15))),
            Map.entry("/api/v1/profile/me/account-action/start", new RateLimit(5, Duration.ofHours(1))),
            Map.entry("/api/v1/profile/me/account-action/confirm", new RateLimit(10, Duration.ofMinutes(15))),
            Map.entry("/api/v1/profile/me/role/google-challenge", new RateLimit(10, Duration.ofMinutes(15))),

            Map.entry("/api/v1/profile/me/role/switch", new RateLimit(10, Duration.ofMinutes(15))),
            Map.entry("/api/v1/landlord/payments/test-destination", new RateLimit(5, Duration.ofHours(1))),
            Map.entry("/api/v1/sublessor/payments/test-destination", new RateLimit(5, Duration.ofHours(1)))
    );

    /**
     * Shared secret that lets the Makao Hub website tell us which visitor a call is for. The website sits in
     * front of every browser sign-in, so without it all website users would share one address and one limit.
     * Unset means the header is ignored and the connecting address is always used.
     */
    private static final String WEB_PROXY_KEY = blankToNull(System.getenv("MAKAO_WEB_PROXY_KEY"));

    // ponytail: per-instance limits reset on restart or cache eviction.
    // Use shared enforcement before deploying multiple replicas.
    private final Cache<String, Bucket> buckets =
            Caffeine.newBuilder()
                    .maximumSize(50_000)
                    .expireAfterAccess(Duration.ofHours(2))
                    .build();

    @Override
    protected boolean shouldNotFilter(
            HttpServletRequest request
    ) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !RATE_LIMITS.containsKey(
                request.getRequestURI()
        );
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

        String bucketKey = clientAddress(request)
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

    /** The visitor's address: the website's forwarded one only when it proves who it is, otherwise the socket's. */
    private static String clientAddress(HttpServletRequest request) {
        if (WEB_PROXY_KEY != null) {
            String presented = request.getHeader("X-Makao-Web-Key");
            String forwarded = request.getHeader("X-Makao-Client-Ip");
            if (presented != null && forwarded != null
                    && MessageDigest.isEqual(
                            presented.getBytes(StandardCharsets.UTF_8),
                            WEB_PROXY_KEY.getBytes(StandardCharsets.UTF_8))
                    && forwarded.length() <= 45
                    && forwarded.matches("[0-9a-fA-F:.]+")) {
                return forwarded;
            }
        }
        return request.getRemoteAddr();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
