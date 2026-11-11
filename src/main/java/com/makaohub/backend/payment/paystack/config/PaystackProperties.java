package com.makaohub.backend.payment.paystack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;

/** Server-only Paystack configuration. The secret key must come from a secret store. */
@ConfigurationProperties(prefix = "makao.paystack")
public record PaystackProperties(
        boolean enabled,
        String baseUrl,
        String secretKey,
        String checkoutReturnUrl
) {
    public static final String RETURN_PATH = "/payments/paystack/return";

    @ConstructorBinding
    public PaystackProperties { }

    public PaystackProperties(boolean enabled, String baseUrl, String secretKey) {
        this(enabled, baseUrl, secretKey, null);
    }

    /** Optional until an owned HTTPS page is available; never send localhost to Paystack. */
    public String validatedCheckoutReturnUrl() {
        if (checkoutReturnUrl == null || checkoutReturnUrl.isBlank()) return null;
        try {
            URI url = URI.create(checkoutReturnUrl);
            boolean secure = "https".equals(url.getScheme()) && url.getHost() != null
                    && url.getHost().contains(".") && !url.getHost().endsWith(".localhost")
                    && !url.getHost().matches("[0-9.]+")
                    && (url.getPort() == -1 || url.getPort() == 443);
            if (!secure || !RETURN_PATH.equals(url.getRawPath())
                    || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return url.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Paystack return URL must be an owned, browser-accessible HTTPS page at /payments/paystack/return.");
        }
    }

    @Override public String toString() { return "PaystackProperties[REDACTED]"; }
}
