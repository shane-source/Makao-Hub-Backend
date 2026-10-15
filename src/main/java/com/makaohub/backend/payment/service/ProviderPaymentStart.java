package com.makaohub.backend.payment.service;

/** Provider checkout details returned when a payment is initialized. */
public record ProviderPaymentStart(
        String providerReference,
        String authorizationUrl,
        String accessCode,
        String displayText
) {
    public ProviderPaymentStart(
            String providerReference,
            String authorizationUrl,
            String accessCode
    ) {
        this(providerReference, authorizationUrl, accessCode, null);
    }

    public ProviderPaymentStart {
        if (providerReference == null || providerReference.isBlank()
                || providerReference.length() > 120) {
            throw new IllegalArgumentException("Provider reference is required.");
        }
        providerReference = providerReference.strip();
        if (authorizationUrl != null && authorizationUrl.length() > 500) {
            throw new IllegalArgumentException("Authorization URL is too long.");
        }
        if (accessCode != null && accessCode.length() > 160) {
            throw new IllegalArgumentException("Provider access code is too long.");
        }
        if (displayText != null && displayText.length() > 500) {
            throw new IllegalArgumentException("Provider display text is too long.");
        }
    }
}
