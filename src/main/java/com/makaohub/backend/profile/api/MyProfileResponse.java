package com.makaohub.backend.profile.api;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;

import java.time.Instant;
import java.util.UUID;

public record MyProfileResponse(
        UUID id,
        String fullName,
        String email,
        String phoneNumber,
        RoleName role,
        AccountStatus accountStatus,
        Instant createdAt,
        String avatarUrl
) {
}