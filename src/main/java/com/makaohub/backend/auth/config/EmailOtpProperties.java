package com.makaohub.backend.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Base64;

@ConfigurationProperties(prefix = "security.email-otp")
public record EmailOtpProperties(String fromEmail, String hmacKey) {
    public EmailOtpProperties {
        if (!validEmail(fromEmail)) throw new IllegalArgumentException("OTP_FROM_EMAIL must be a plain email address.");
        try {
            if (hmacKey == null || Base64.getDecoder().decode(hmacKey).length != 32) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("OTP_HMAC_KEY must contain 32 random bytes encoded as Base64.");
        }
    }
    public static boolean validEmail(String email) {
        return email != null && email.length() <= 320 && email.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+");
    }
    @Override public String toString() { return "EmailOtpProperties[redacted]"; }
}
