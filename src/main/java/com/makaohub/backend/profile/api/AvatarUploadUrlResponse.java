package com.makaohub.backend.profile.api;

public record AvatarUploadUrlResponse(
        String uploadUrl,
        String objectPath,
        String contentType
) {
}