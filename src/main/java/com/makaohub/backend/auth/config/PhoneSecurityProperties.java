package com.makaohub.backend.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "security.phone")
public record PhoneSecurityProperties(

        @NotBlank
        String encryptionKey,

        @NotBlank
        String lookupHmacKey

) {
}