package com.makaohub.backend.profile.api;

import com.makaohub.backend.auth.domain.RoleName;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record SwitchRoleRequest(
        @NotNull(message = "Target role is required.")
        RoleName targetRole,

        @Size(max = 72, message = "Password is too long.")
        String currentPassword,

        @Size(max = 8192, message = "Google ID token is too long.")
        String googleIdToken,

        UUID googleChallengeId
) {
    @AssertTrue(message = "Choose LANDLORD, RENTER or SUBLESSOR.")
    public boolean isPublicRole() {
        return targetRole == RoleName.LANDLORD
                || targetRole == RoleName.RENTER
                || targetRole == RoleName.SUBLESSOR;
    }

    @AssertTrue(message = "Provide either a password or a Google token with its challenge ID.")
    public boolean isSingleAuthenticationMethod() {
        boolean passwordOnly = currentPassword != null
                && !currentPassword.isBlank()
                && googleIdToken == null
                && googleChallengeId == null;

        boolean googleOnly = currentPassword == null
                && googleIdToken != null
                && !googleIdToken.isBlank()
                && googleChallengeId != null;

        return passwordOnly || googleOnly;
    }

    @AssertTrue(message = "Password must not exceed 72 UTF-8 bytes.")
    public boolean isPasswordWithinByteLimit() {
        return currentPassword == null
                || currentPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() {
        return "SwitchRoleRequest[credentials=REDACTED]";
    }
}