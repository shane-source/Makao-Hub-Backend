package com.makaohub.backend.auth.api;

import com.makaohub.backend.auth.domain.RoleName;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GoogleRegistrationRequest(

        @NotBlank
        @Size(max = 8192)
        String idToken,

        @NotBlank
        @Size(min = 2, max = 150)
        String fullName,

        @NotBlank
        @Pattern(
                regexp = "^(?:\\+254|254|0)(?:1|7)\\d{8}$",
                message = "Phone number must be a valid Kenyan mobile number."
        )
        String phoneNumber,

        @NotNull
        RoleName role,

        @AssertTrue(
                message = "Terms of Service and Privacy Policy "
                        + "must be accepted."
        )
        boolean termsAndPrivacyAccepted

) {
}