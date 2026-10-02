package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.config.JwtSecurityProperties;
import com.makaohub.backend.auth.domain.RefreshToken;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-09-27T10:00:00Z");

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        JwtSecurityProperties properties =
                new JwtSecurityProperties(
                        "makao-hub-backend",
                        "makao-hub-api",
                        "test-key",
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        "unused-in-this-test",
                        "unused-in-this-test"
                );

        Clock fixedClock = Clock.fixed(
                NOW,
                ZoneOffset.UTC
        );

        refreshTokenService = new RefreshTokenService(
                refreshTokenRepository,
                properties,
                fixedClock
        );
    }

    @Test
    void reusedRotatedTokenRevokesItsFamily() {
        UUID familyId = UUID.randomUUID();
        User user = mock(User.class);

        RefreshToken oldToken = new RefreshToken(
                user,
                "stored-hash",
                familyId,
                NOW.plus(Duration.ofDays(30))
        );

        oldToken.markUsed(
                mock(RefreshToken.class),
                NOW.minusSeconds(1)
        );

        when(refreshTokenRepository.findByTokenHashForUpdate(
                anyString()
        )).thenReturn(Optional.of(oldToken));

        AuthException exception = assertThrows(
                AuthException.class,
                () -> refreshTokenService.rotate(
                        "reused-raw-token"
                )
        );

        assertEquals(
                AuthException.Reason.INVALID_REFRESH_TOKEN,
                exception.getReason()
        );

        verify(refreshTokenRepository)
                .revokeActiveTokenFamily(
                        familyId,
                        NOW,
                        "ROTATED_TOKEN_REUSE"
                );
    }
}