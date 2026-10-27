package com.makaohub.backend.auth.api;

import com.makaohub.backend.auth.domain.RoleName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(

        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be valid.")
        @Size(
                max = 320,
                message = "Email cannot exceed 320 characters."
        )
        String email,

        @NotBlank(message = "Password is required.")
        @Size(
                max = 72,
                message = "Password cannot exceed 72 characters."
        )
        String password,

        // A comparison constraint, never a role assignment. Public login requires it.
        RoleName expectedRole

) {
    // Staff login uses its separate invite-only authorization checks.
    public LoginRequest(String email, String password) {
        this(email, password, null);
    }
}
