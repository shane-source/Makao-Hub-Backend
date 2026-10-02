package com.makaohub.backend.storage.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "storage.supabase")
public record SupabaseStorageProperties(

        @NotBlank
        String url,

        @NotBlank
        String serviceRoleKey,

        @NotBlank
        String avatarBucket,

        @NotBlank
        String verificationBucket

) {
}