package com.makaohub.backend.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "security.google")
public record GoogleSecurityProperties(
        @NotBlank String clientId
) {
}