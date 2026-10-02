package com.makaohub.backend.auth.api;

import com.makaohub.backend.auth.domain.RoleName;

import java.time.Instant;
import java.util.UUID;

public record AuthTokensResponse(
        String tokenType,
        String accessToken,
        long expiresIn,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UUID userId,
        String fullName,
        String email,
        RoleName role
) {
}