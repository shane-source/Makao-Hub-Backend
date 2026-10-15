package com.makaohub.backend.payment.daraja.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration needed by the Daraja adapter.
 *
 * Credentials intentionally have no in-repository defaults. The mock payment
 * profile can therefore start without Daraja credentials, while the real
 * adapter can validate them before making an outbound request.
 */
@ConfigurationProperties(prefix = "makao.daraja")
public record DarajaProperties(

        boolean enabled,

        String baseUrl,

        String consumerKey,

        String consumerSecret,

        String shortCode,

        String passkey,

        String callbackUrl
) {
}
