package com.makaohub.backend.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoogleRoleSwitchVerifierTest {

    private static final Instant ISSUED_AT =
            Instant.parse("2026-10-05T10:00:00Z");

    private static final String NONCE = "a".repeat(43);

    @Test
    void acceptsValidGoogleConfirmationToken() {
        JwtDecoder decoder = mock(JwtDecoder.class);

        GoogleIdTokenVerifier verifier =
                new GoogleIdTokenVerifier(decoder);

        Jwt token = Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject("google-subject")
                .issuedAt(ISSUED_AT)
                .expiresAt(ISSUED_AT.plusSeconds(300))
                .claim("nonce", NONCE)
                .build();

        when(decoder.decode("valid-token"))
                .thenReturn(token);

        GoogleIdTokenVerifier.VerifiedRoleSwitchIdentity result =
                verifier.verifyForRoleSwitch("valid-token");

        assertEquals(
                "google-subject",
                result.subject()
        );

        assertEquals(
                NONCE,
                result.nonce()
        );

        assertEquals(
                ISSUED_AT,
                result.issuedAt()
        );

        assertEquals(
                ISSUED_AT.plusSeconds(300),
                result.expiresAt()
        );
    }

    @Test
    void rejectsInvalidSignature() {
        JwtDecoder decoder = mock(JwtDecoder.class);

        GoogleIdTokenVerifier verifier =
                new GoogleIdTokenVerifier(decoder);

        when(decoder.decode("invalid-token"))
                .thenThrow(new JwtException("Invalid signature"));

        assertThrows(
                JwtException.class,
                () -> verifier.verifyForRoleSwitch("invalid-token")
        );
    }

    @Test
    void rejectsMissingNonce() {
        JwtDecoder decoder = mock(JwtDecoder.class);

        GoogleIdTokenVerifier verifier =
                new GoogleIdTokenVerifier(decoder);

        Jwt token = Jwt.withTokenValue("missing-nonce")
                .header("alg", "RS256")
                .subject("google-subject")
                .issuedAt(ISSUED_AT)
                .expiresAt(ISSUED_AT.plusSeconds(300))
                .build();

        when(decoder.decode("missing-nonce"))
                .thenReturn(token);

        assertThrows(
                JwtException.class,
                () -> verifier.verifyForRoleSwitch("missing-nonce")
        );
    }

    @Test
    void rejectsMalformedNonce() {
        JwtDecoder decoder = mock(JwtDecoder.class);

        GoogleIdTokenVerifier verifier =
                new GoogleIdTokenVerifier(decoder);

        Jwt token = Jwt.withTokenValue("bad-nonce")
                .header("alg", "RS256")
                .subject("google-subject")
                .issuedAt(ISSUED_AT)
                .expiresAt(ISSUED_AT.plusSeconds(300))
                .claim("nonce", "short")
                .build();

        when(decoder.decode("bad-nonce"))
                .thenReturn(token);

        assertThrows(
                JwtException.class,
                () -> verifier.verifyForRoleSwitch("bad-nonce")
        );
    }

    @Test
    void rejectsInvalidInputBeforeDecoderCall() {
        JwtDecoder decoder = mock(JwtDecoder.class);

        GoogleIdTokenVerifier verifier =
                new GoogleIdTokenVerifier(decoder);

        assertThrows(
                JwtException.class,
                () -> verifier.verifyForRoleSwitch(null)
        );

        assertThrows(
                JwtException.class,
                () -> verifier.verifyForRoleSwitch("")
        );

        assertThrows(
                JwtException.class,
                () -> verifier.verifyForRoleSwitch(" ")
        );

        assertThrows(
                JwtException.class,
                () -> verifier.verifyForRoleSwitch("a".repeat(8193))
        );

        verifyNoInteractions(decoder);
    }
}