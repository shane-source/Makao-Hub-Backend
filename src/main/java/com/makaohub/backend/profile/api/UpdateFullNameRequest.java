package com.makaohub.backend.profile.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateFullNameRequest(

        @NotBlank(message = "Full name is required.")
        @Size(
                min = 2,
                max = 150,
                message = "Full name must contain between 2 and 150 characters."
        )
        String fullName

) {
    public UpdateFullNameRequest {
        if (fullName != null) {
            fullName = fullName.strip().replaceAll("\\s+", " ");
        }
    }
}