package com.makaohub.backend.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "security.twilio-verify")
public record TwilioVerifyProperties(

        @NotBlank
        String apiKeySid,

        @NotBlank
        String apiKeySecret,

        @NotBlank
        String serviceSid

) {
}