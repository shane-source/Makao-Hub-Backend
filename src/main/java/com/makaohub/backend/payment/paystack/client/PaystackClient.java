package com.makaohub.backend.payment.paystack.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.makaohub.backend.payment.paystack.config.PaystackProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Objects;

/** Minimal Paystack API client. It never exposes the secret key or provider body. */
@Service
public class PaystackClient {

    private final PaystackProperties properties;
    private final RestClient restClient;

    public PaystackClient(PaystackProperties properties) {
        this.properties = Objects.requireNonNull(properties);
        this.restClient = RestClient.builder()
                .baseUrl(httpsBaseUrl(properties.baseUrl()))
                .build();
    }

    public ChargeStart charge(
            String email,
            long amountMinor,
            String reference,
            String phoneNumber
    ) {
        requireEnabled();
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Paystack amount must be positive.");
        }
        try {
            ChargeEnvelope response = restClient.post()
                    .uri("/charge")
                    .headers(headers -> {
                        headers.setBearerAuth(secretKey());
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .body(new ChargeRequest(
                            email,
                            Long.toString(amountMinor),
                            "KES",
                            new MobileMoney(phoneNumber, "mpesa"),
                            reference
                    ))
                    .retrieve()
                    .body(ChargeEnvelope.class);

            if (response == null || !response.status() || response.data() == null
                    || response.data().reference() == null
                    || response.data().reference().isBlank()) {
                throw new IllegalStateException("Paystack did not create the charge.");
            }
            return new ChargeStart(
                    response.data().reference(),
                    response.data().status(),
                    response.data().displayText()
            );
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("Paystack charge request failed.", exception);
        }
    }

    public VerificationResult verify(String reference) {
        requireEnabled();
        try {
            VerifyEnvelope response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/transaction/verify/{reference}")
                            .build(reference))
                    .headers(headers -> {
                        headers.setBearerAuth(secretKey());
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .retrieve()
                    .body(VerifyEnvelope.class);

            if (response == null || !response.status() || response.data() == null) {
                throw new IllegalStateException("Paystack verification returned no payment data.");
            }
            return new VerificationResult(
                    response.data().status(),
                    response.data().reference(),
                    response.data().amount(),
                    response.data().currency()
            );
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("Paystack verification request failed.", exception);
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Paystack integration is disabled.");
        }
    }

    private String secretKey() {
        String key = required(properties.secretKey(), "MAKAO_PAYSTACK_SECRET_KEY");
        if (!key.startsWith("sk_test_") && !key.startsWith("sk_live_")) {
            throw new IllegalStateException("MAKAO_PAYSTACK_SECRET_KEY is not a Paystack secret key.");
        }
        return key;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not configured.");
        }
        return value.strip();
    }

    private static String httpsBaseUrl(String value) {
        String url = required(value, "MAKAO_PAYSTACK_BASE_URL");
        if (!url.startsWith("https://")) {
            throw new IllegalStateException("MAKAO_PAYSTACK_BASE_URL must use HTTPS.");
        }
        return url;
    }

    public record ChargeStart(String reference, String status, String displayText) { }

    public record VerificationResult(
            String status,
            String reference,
            long amountMinor,
            String currency
    ) { }

    private record ChargeRequest(
            String email,
            String amount,
            String currency,
            @JsonProperty("mobile_money") MobileMoney mobileMoney,
            String reference
    ) { }

    private record MobileMoney(String phone, String provider) { }

    private record ChargeEnvelope(boolean status, String message, ChargeData data) { }

    private record ChargeData(
            String reference,
            String status,
            @JsonProperty("display_text") String displayText
    ) { }

    private record VerifyEnvelope(boolean status, String message, VerifyData data) { }

    private record VerifyData(
            String status,
            String reference,
            long amount,
            String currency
    ) { }
}
