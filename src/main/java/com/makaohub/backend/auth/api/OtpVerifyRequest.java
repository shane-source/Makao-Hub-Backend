package com.makaohub.backend.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import com.makaohub.backend.auth.exception.AuthException;
import java.util.UUID;

public record OtpVerifyRequest(
        @NotBlank(message = "Challenge ID is required.") String challengeId,
        @NotBlank(message = "Verification code is required.")
        @Pattern(regexp = "\\d{6}", message = "Verification code must contain six digits.") String code
) {
    public UUID parsedChallengeId() {
        try { return UUID.fromString(challengeId); }
        catch (IllegalArgumentException exception) {
            throw new AuthException(AuthException.Reason.INVALID_OTP, "The verification code is invalid or expired.");
        }
    }
}
