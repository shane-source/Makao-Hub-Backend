package com.makaohub.backend.storage.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "security.uploads")
public record UploadSecurityProperties(

        @Min(1)
        long avatarMaxBytes,

        @Min(1)
        long verificationDocumentMaxBytes,

        @Min(1)
        long avatarUploadUrlsPerHour,

        @Min(1)
        long avatarConfirmationsPerHour,

        @Min(1)
        long verificationUploadUrlsPerHour,

        @Min(1)
        long verificationConfirmationsPerHour,

        @Min(1)
        long verificationSubmissionsPerDay

) {
}