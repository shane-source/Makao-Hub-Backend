package com.makaohub.backend.auth.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "security.jwt")
public record JwtSecurityProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotBlank String keyId,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotBlank String privateKey,
        @NotBlank String publicKey
) {

    @AssertTrue(
            message = "Access-token lifetime must be greater than zero."
    )
    public boolean isAccessTokenTtlValid() {
        return isPositive(accessTokenTtl);
    }

    @AssertTrue(
            message = "Refresh-token lifetime must be greater than "
                    + "the access-token lifetime."
    )
    public boolean isRefreshTokenTtlValid() {
        return isPositive(refreshTokenTtl)
                && accessTokenTtl != null
                && refreshTokenTtl.compareTo(accessTokenTtl) > 0;
    }

    private static boolean isPositive(Duration duration) {
        return duration != null
                && !duration.isZero()
                && !duration.isNegative();
    }
}