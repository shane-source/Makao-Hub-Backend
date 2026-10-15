package com.makaohub.backend.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "Email is required.") @Email(message = "Email must be valid.")
        @Size(max = 320, message = "Email cannot exceed 320 characters.") String email,
        @NotBlank(message = "Verification code is required.")
        @Pattern(regexp = "\\d{6}", message = "Verification code must contain six digits.") String code,
        @NotBlank(message = "New password is required.")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters.") String newPassword
) { }
