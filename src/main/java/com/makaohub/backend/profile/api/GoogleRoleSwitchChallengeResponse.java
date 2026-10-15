package com.makaohub.backend.profile.api;

import com.makaohub.backend.auth.domain.RoleName;

import java.time.Instant;
import java.util.UUID;

public record GoogleRoleSwitchChallengeResponse(
        UUID challengeId,
        RoleName targetRole,
        String nonce,
        Instant expiresAt
) {

    @Override
    public String toString() {
        return "GoogleRoleSwitchChallengeResponse[REDACTED]";
    }
}