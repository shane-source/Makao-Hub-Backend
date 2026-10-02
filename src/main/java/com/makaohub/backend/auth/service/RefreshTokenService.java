package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.config.JwtSecurityProperties;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RefreshToken;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final int TOKEN_SIZE_BYTES = 32;

    private static final String REUSE_REASON =
            "ROTATED_TOKEN_REUSE";

    private static final String EXPIRED_REASON =
            "TOKEN_EXPIRED";

    private static final String ACCOUNT_REASON =
            "ACCOUNT_UNAVAILABLE";

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtSecurityProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            JwtSecurityProperties properties,
            Clock clock
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedRefreshToken issue(User user) {
        Objects.requireNonNull(user, "User cannot be null.");

        if (user.getId() == null) {
            throw new IllegalArgumentException(
                    "Refresh tokens require a persisted user."
            );
        }

        Instant expiresAt = Instant.now(clock).plus(
                properties.refreshTokenTtl()
        );

        String rawToken = generateRawToken();

        RefreshToken refreshToken = new RefreshToken(
                user,
                hashToken(rawToken),
                UUID.randomUUID(),
                expiresAt
        );

        refreshTokenRepository.save(refreshToken);

        return new IssuedRefreshToken(
                rawToken,
                expiresAt
        );
    }

    @Transactional(noRollbackFor = AuthException.class)
    public RotatedRefreshToken rotate(String rawToken) {
        Instant now = Instant.now(clock);

        RefreshToken currentToken =
                refreshTokenRepository.findByTokenHashForUpdate(
                        hashToken(rawToken)
                ).orElseThrow(this::invalidRefreshToken);

        if (currentToken.isUsed()
                || currentToken.isRevoked()) {
            revokeFamily(
                    currentToken,
                    now,
                    REUSE_REASON
            );

            throw invalidRefreshToken();
        }

        if (currentToken.isExpired(now)) {
            revokeFamily(
                    currentToken,
                    now,
                    EXPIRED_REASON
            );

            throw invalidRefreshToken();
        }

        User user = currentToken.getUser();

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            revokeFamily(
                    currentToken,
                    now,
                    ACCOUNT_REASON
            );

            throw new AuthException(
                    AuthException.Reason.ACCOUNT_UNAVAILABLE,
                    "This account is unavailable."
            );
        }

        String replacementRawToken = generateRawToken();

        Instant replacementExpiresAt = now.plus(
                properties.refreshTokenTtl()
        );

        RefreshToken replacementToken =
                refreshTokenRepository.save(
                        new RefreshToken(
                                user,
                                hashToken(replacementRawToken),
                                currentToken.getTokenFamilyId(),
                                replacementExpiresAt
                        )
                );

        currentToken.markUsed(
                replacementToken,
                now
        );

        return new RotatedRefreshToken(
                user,
                new IssuedRefreshToken(
                        replacementRawToken,
                        replacementExpiresAt
                )
        );
    }

    @Transactional
    public void revokeTokenFamily(String rawToken) {
        refreshTokenRepository.findByTokenHashForUpdate(
                hashToken(rawToken)
        ).ifPresent(refreshToken -> revokeFamily(
                refreshToken,
                Instant.now(clock),
                LOGOUT_REASON
        ));
    }

    private void revokeFamily(
            RefreshToken refreshToken,
            Instant revokedAt,
            String reason
    ) {
        refreshTokenRepository.revokeActiveTokenFamily(
                refreshToken.getTokenFamilyId(),
                revokedAt,
                reason
        );
    }

    private String generateRawToken() {
        byte[] randomBytes = new byte[TOKEN_SIZE_BYTES];
        secureRandom.nextBytes(randomBytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    rawToken.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available.",
                    exception
            );
        }
    }



    private AuthException invalidRefreshToken() {
        return new AuthException(
                AuthException.Reason.INVALID_REFRESH_TOKEN,
                "The refresh token is invalid or expired."
        );
    }

    private static final String LOGOUT_REASON =
            "USER_LOGOUT";

    public record IssuedRefreshToken(
            String value,
            Instant expiresAt
    ) {
    }

    public record RotatedRefreshToken(
            User user,
            IssuedRefreshToken refreshToken
    ) {
    }
}