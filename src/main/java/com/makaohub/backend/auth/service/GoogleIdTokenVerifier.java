package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.config.GoogleSecurityProperties;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

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

    private final JwtDecoder jwtDecoder;

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
    }

    public VerifiedGoogleIdentity verify(String idToken) {
        Jwt jwt = jwtDecoder.decode(idToken);

        String fullName = jwt.getClaimAsString("name");

        return new VerifiedGoogleIdentity(
                jwt.getSubject(),
                jwt.getClaimAsString("email")
                        .trim()
                        .toLowerCase(Locale.ROOT),
                fullName == null ? null : fullName.strip()
        );
    }

    private OAuth2TokenValidatorResult validateIssuer(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");

        if (ALLOWED_ISSUERS.contains(issuer)) {
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
}