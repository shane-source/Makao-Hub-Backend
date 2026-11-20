package com.makaohub.backend.profile.service;

import com.makaohub.backend.auth.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Signs an account out of every device by revoking all of its active refresh tokens. */
@Service
public class SessionRevocationService {
    private static final String REASON = "LOGOUT_ALL_DEVICES";

    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public SessionRevocationService(RefreshTokenRepository refreshTokens, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional
    public int revokeAll(UUID userId) {
        return refreshTokens.revokeAllActiveForUserWithReason(userId, Instant.now(clock), REASON);
    }
}
