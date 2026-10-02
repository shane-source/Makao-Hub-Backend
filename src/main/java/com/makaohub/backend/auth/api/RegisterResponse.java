package com.makaohub.backend.auth.api;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;

import java.time.Instant;
import java.util.UUID;

public record RegisterResponse(
        UUID userId,
        String fullName,
        String email,
        RoleName role,
        AccountStatus status,
        Instant createdAt
) {
}