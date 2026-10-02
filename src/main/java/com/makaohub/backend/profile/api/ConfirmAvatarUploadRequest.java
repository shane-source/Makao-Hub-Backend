package com.makaohub.backend.profile.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmAvatarUploadRequest(

        @NotBlank(message = "Object path is required.")
        @Size(max = 255)
        String objectPath
) {
    public ConfirmAvatarUploadRequest {
        if (objectPath != null) {
            objectPath = objectPath.strip();
        }
    }
}