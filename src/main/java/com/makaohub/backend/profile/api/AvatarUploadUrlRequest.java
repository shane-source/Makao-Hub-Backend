package com.makaohub.backend.profile.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Locale;

public record AvatarUploadUrlRequest(

        @NotBlank(message = "Content type is required.")
        @Pattern(
                regexp = "image/(jpeg|png|webp)",
                message = "Avatar must be a JPEG, PNG, or WebP image."
        )
        String contentType
) {
    public AvatarUploadUrlRequest {
        if (contentType != null) {
            contentType = contentType.strip()
                    .toLowerCase(Locale.ROOT);
        }
    }
}