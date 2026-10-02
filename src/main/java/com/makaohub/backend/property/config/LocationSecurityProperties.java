package com.makaohub.backend.property.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "security.location")
public record LocationSecurityProperties(

        @NotBlank
        String encryptionKey

) {
}