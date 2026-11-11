package com.makaohub.backend.profile.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Completes only the authenticated account's missing phone; not a payout destination change. */
public record CompletePhoneRequest(
        @NotBlank
        @Pattern(regexp = "^(?:\\+254|254|0)(?:1|7)\\d{8}$",
                message = "Phone number must be a valid Kenyan mobile number.")
        String phoneNumber
) {
    @Override public String toString() { return "CompletePhoneRequest[REDACTED]"; }
}
