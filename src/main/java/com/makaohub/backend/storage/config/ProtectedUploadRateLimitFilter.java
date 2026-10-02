package com.makaohub.backend.auth.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class ProtectedUploadRateLimitFilter
        extends OncePerRequestFilter {

    private final UploadSecurityProperties limits;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofDays(2))
            .build();

    public ProtectedUploadRateLimitFilter(
            UploadSecurityProperties limits
    ) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || ruleFor(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Rule rule = ruleFor(request.getRequestURI());

        String bucketKey = authenticatedUserKey(request)
                + "|"
                + rule.key();

        Bucket bucket = buckets.get(
                bucketKey,
                ignored -> createBucket(rule)
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

    private Rule ruleFor(String path) {
        return switch (path) {
            case "/api/v1/profile/me/avatar/upload-url" ->
                    new Rule(
                            "avatar-upload-url",
                            limits.avatarUploadUrlsPerHour(),
                            Duration.ofHours(1)
                    );

            case "/api/v1/profile/me/avatar/confirm" ->
                    new Rule(
                            "avatar-confirm",
                            limits.avatarConfirmationsPerHour(),
                            Duration.ofHours(1)
                    );

            case "/api/v1/landlord/verification/documents/upload-url" ->
                    new Rule(
                            "verification-upload-url",
                            limits.verificationUploadUrlsPerHour(),
                            Duration.ofHours(1)
                    );

            case "/api/v1/landlord/verification/submit" ->
                    new Rule(
                            "verification-submit",
                            limits.verificationSubmissionsPerDay(),
                            Duration.ofDays(1)
                    );

            default -> isDocumentConfirmationPath(path)
                    ? new Rule(
                    "verification-document-confirm",
                    limits.verificationConfirmationsPerHour(),
                    Duration.ofHours(1)
            )
                    : null;
        };
    }

    private boolean isDocumentConfirmationPath(String path) {
        return path.startsWith(
                "/api/v1/landlord/verification/documents/"
        ) && path.endsWith("/confirm");
    }

    private String authenticatedUserKey(
            HttpServletRequest request
    ) {
        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication != null
                && authentication.getPrincipal() instanceof Jwt jwt
                && jwt.getSubject() != null) {
            return jwt.getSubject();
        }

        return request.getRemoteAddr();
    }

    private Bucket createBucket(Rule rule) {
        return Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(rule.capacity())
                        .refillGreedy(
                                rule.capacity(),
                                rule.refillPeriod()
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
                  "detail": "Too many upload or verification requests. Try again later.",
                  "code": "RATE_LIMIT_EXCEEDED"
                }
                """);
    }

    private record Rule(
            String key,
            long capacity,
            Duration refillPeriod
    ) {
    }
}