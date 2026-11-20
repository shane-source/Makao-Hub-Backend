package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.config.GoogleSecurityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

@Service
public class GoogleIdTokenVerifier {

    private static final String GOOGLE_JWK_SET_URI =
            "https://www.googleapis.com/oauth2/v3/certs";

    private static final Set<String> ALLOWED_ISSUERS = Set.of(
            "https://accounts.google.com",
            "accounts.google.com"
    );

    private static final OAuth2Error INVALID_ISSUER =
            new OAuth2Error(
                    "invalid_token",
                    "Google ID token has an invalid issuer.",
                    null
            );

    private static final OAuth2Error INVALID_IDENTITY =
            new OAuth2Error(
                    "invalid_token",
                    "Google ID token does not contain a verified identity.",
                    null
            );

    private static final Logger LOG = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);

    private final JwtDecoder jwtDecoder;
    private final String expectedClientId;

    @Autowired
    public GoogleIdTokenVerifier(
            GoogleSecurityProperties properties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(GOOGLE_JWK_SET_URI)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> issuerValidator =
                this::validateIssuer;

        OAuth2TokenValidator<Jwt> audienceValidator =
                new JwtAudienceValidator(properties.clientId());

        OAuth2TokenValidator<Jwt> identityValidator =
                this::validateIdentity;

        decoder.setJwtValidator(
                JwtValidators.createDefaultWithValidators(
                        issuerValidator,
                        audienceValidator,
                        identityValidator
                )
        );

        this.jwtDecoder = decoder;
        this.expectedClientId = properties.clientId();
    }

    // Tests can supply a decoder without contacting Google.
    GoogleIdTokenVerifier(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
        this.expectedClientId = "";
    }

    public VerifiedGoogleIdentity verify(String idToken) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(idToken);
        } catch (JwtException exception) {
            logRejection(idToken, exception);
            throw exception;
        }

        String fullName = jwt.getClaimAsString("name");

        return new VerifiedGoogleIdentity(
                jwt.getSubject(),
                jwt.getClaimAsString("email")
                        .trim()
                        .toLowerCase(Locale.ROOT),
                fullName == null ? null : fullName.strip()
        );
    }

    /**
     * Says why Google sign-in failed. Logs the validator's message and the token's audience and issuer, which
     * are public claims. The token itself and the person's details are never logged.
     */
    private void logRejection(String idToken, JwtException exception) {
        String audience = "?";
        String issuer = "?";
        String expires = "?";
        try {
            String[] parts = idToken.split("\\.");
            JsonNode claims = new ObjectMapper().readTree(
                    Base64.getUrlDecoder().decode(parts[1]));
            audience = claims.path("aud").asText("?");
            issuer = claims.path("iss").asText("?");
            expires = claims.path("exp").asText("?");
        } catch (RuntimeException | java.io.IOException ignored) {
            // Not a readable token; the validator message below still says why.
        }
        LOG.warn("Google ID token rejected: {} | token audience={} issuer={} exp={} | backend expects audience={} | now={}",
                exception.getMessage(), audience, issuer, expires,
                expectedClientId.isEmpty() ? "(not set)" : expectedClientId, Instant.now().getEpochSecond());
    }

    public VerifiedRoleSwitchIdentity verifyForRoleSwitch(
            String idToken
    ) {
        if (idToken == null
                || idToken.isBlank()
                || idToken.length() > 8192) {
            throw new JwtException(
                    "Google confirmation failed."
            );
        }

        Jwt jwt = jwtDecoder.decode(idToken);

        String nonce = jwt.getClaimAsString("nonce");
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();

        if (nonce == null
                || !nonce.matches("[A-Za-z0-9_-]{43}")
                || issuedAt == null
                || expiresAt == null
                || !expiresAt.isAfter(issuedAt)) {
            throw new JwtException(
                    "Google confirmation failed."
            );
        }

        return new VerifiedRoleSwitchIdentity(
                jwt.getSubject(),
                nonce,
                issuedAt,
                expiresAt
        );
    }

    private OAuth2TokenValidatorResult validateIssuer(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");

        if (issuer != null && ALLOWED_ISSUERS.contains(issuer)) {
            return OAuth2TokenValidatorResult.success();
        }

        return OAuth2TokenValidatorResult.failure(
                INVALID_ISSUER
        );
    }

    private OAuth2TokenValidatorResult validateIdentity(Jwt jwt) {
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        Boolean emailVerified =
                jwt.getClaimAsBoolean("email_verified");

        if (hasText(subject)
                && hasText(email)
                && Boolean.TRUE.equals(emailVerified)) {
            return OAuth2TokenValidatorResult.success();
        }

        return OAuth2TokenValidatorResult.failure(
                INVALID_IDENTITY
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record VerifiedGoogleIdentity(
            String subject,
            String email,
            String fullName
    ) {
    }

    public record VerifiedRoleSwitchIdentity(
            String subject,
            String nonce,
            Instant issuedAt,
            Instant expiresAt
    ) {
        @Override
        public String toString() {
            return "VerifiedRoleSwitchIdentity[REDACTED]";
        }
    }
}