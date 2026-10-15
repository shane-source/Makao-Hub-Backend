package com.makaohub.backend.payment.daraja.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.makaohub.backend.payment.daraja.config.DarajaProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Retrieves and briefly caches the Daraja OAuth access token in memory.
 *
 * Daraja tokens are account-wide, so replacing a token on every request can
 * invalidate a token another request is still using. The small synchronized
 * refresh section prevents that race without persisting the token anywhere.
 */
@Service
public class DarajaOAuthClient {

    private static final Duration REFRESH_SAFETY_WINDOW = Duration.ofSeconds(30);

    private final DarajaProperties properties;
    private final Object refreshMonitor = new Object();
    private volatile CachedToken cachedToken;

    public DarajaOAuthClient(DarajaProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    public String accessToken() {
        Instant now = Instant.now();
        CachedToken current = cachedToken;
        if (current != null && current.validAt(now)) {
            return current.value();
        }

        synchronized (refreshMonitor) {
            now = Instant.now();
            current = cachedToken;
            if (current != null && current.validAt(now)) {
                return current.value();
            }

            cachedToken = requestToken(now);
            return cachedToken.value();
        }
    }

    private CachedToken requestToken(Instant requestedAt) {
        if (!properties.enabled()) {
            throw new IllegalStateException("Daraja integration is disabled.");
        }

        String baseUrl = required(properties.baseUrl(), "DARAJA_BASE_URL");
        String consumerKey = required(
                properties.consumerKey(), "DARAJA_CONSUMER_KEY"
        );
        String consumerSecret = required(
                properties.consumerSecret(), "DARAJA_CONSUMER_SECRET"
        );

        try {
            TokenResponse response = RestClient.builder()
                    .baseUrl(baseUrl)
                    .build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/oauth/v1/generate")
                            .queryParam("grant_type", "client_credentials")
                            .build())
                    .headers(headers -> {
                        headers.setBasicAuth(consumerKey, consumerSecret);
                        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
                    })
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.accessToken() == null
                    || response.accessToken().isBlank()) {
                throw new IllegalStateException("Daraja returned no access token.");
            }

            long expiresInSeconds = parseExpiry(response.expiresIn());
            Instant expiresAt = requestedAt.plusSeconds(expiresInSeconds);
            return new CachedToken(response.accessToken().strip(), expiresAt);
        } catch (RestClientResponseException exception) {
            // Do not expose the provider response body; it may contain credentials
            // or provider-specific data that should not reach logs or clients.
            throw new IllegalStateException("Daraja authentication failed.", exception);
        }
    }

    private static long parseExpiry(String expiresIn) {
        try {
            long seconds = Long.parseLong(required(expiresIn, "expires_in"));
            if (seconds <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return seconds;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Daraja returned an invalid token expiry.");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not configured.");
        }
        return value.strip();
    }

    private record CachedToken(String value, Instant expiresAt) {
        private boolean validAt(Instant now) {
            return !value.isBlank()
                    && now.isBefore(expiresAt.minus(REFRESH_SAFETY_WINDOW));
        }
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") String expiresIn
    ) {
    }
}
