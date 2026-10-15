package com.makaohub.backend.payment.paystack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-only Paystack configuration. The secret key must come from a secret store. */
@ConfigurationProperties(prefix = "makao.paystack")
public record PaystackProperties(
        boolean enabled,
        String baseUrl,
        String secretKey
) {
}
