package com.makaohub.backend.payment.paystack.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.makaohub.backend.payment.paystack.config.PaystackProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Objects;
import java.net.URI;

/** Minimal Paystack API client. It never exposes the secret key or provider body. */
@Service
public class PaystackClient {

    private final PaystackProperties properties;
    private final RestClient restClient;

    @Autowired
    public PaystackClient(PaystackProperties properties) {
        this(properties, RestClient.builder()
                .baseUrl(httpsBaseUrl(properties.baseUrl()))
                .build());
    }

    PaystackClient(PaystackProperties properties, RestClient restClient) {
        this.properties = Objects.requireNonNull(properties);
        this.restClient = Objects.requireNonNull(restClient);
    }

    public CheckoutStart initialize(
            String email,
            long amountMinor,
            String reference
    ) {
        requireEnabled();
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Paystack amount must be positive.");
        }
        String key = secretKey();
        String checkoutReturnUrl = properties.validatedCheckoutReturnUrl();
        try {
            CheckoutEnvelope response = restClient.post()
                    .uri("/transaction/initialize")
                    .headers(headers -> {
                        headers.setBearerAuth(key);
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .body(new CheckoutRequest(
                            email,
                            Long.toString(amountMinor),
                            "KES",
                            reference,
                            List.of("mobile_money"),
                            checkoutReturnUrl
                    ))
                    .retrieve()
                    .body(CheckoutEnvelope.class);

            if (response == null || !response.status() || response.data() == null
                    || !reference.equals(response.data().reference())
                    || !isCheckoutUrl(response.data().authorizationUrl())) {
                throw new IllegalStateException("Paystack did not return a valid hosted checkout.");
            }
            return new CheckoutStart(
                    response.data().reference(),
                    response.data().authorizationUrl(),
                    response.data().accessCode()
            );
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("Paystack checkout initialization failed.", exception);
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
        if (!key.startsWith("sk_test_")) {
            throw new IllegalStateException("Only a Paystack test secret key is supported by this test-only payment flow.");
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

    private static boolean isCheckoutUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI url = URI.create(value);
            return "https".equalsIgnoreCase(url.getScheme())
                    && "checkout.paystack.com".equalsIgnoreCase(url.getHost())
                    && url.getRawUserInfo() == null
                    && (url.getPort() == -1 || url.getPort() == 443)
                    && url.getRawPath() != null
                    && url.getRawPath().matches("/[A-Za-z0-9-]+")
                    && url.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record CheckoutStart(String reference, String authorizationUrl, String accessCode) { }

    public record VerificationResult(
            String status,
            String reference,
            long amountMinor,
            String currency
    ) { }

    private record CheckoutRequest(
            String email,
            String amount,
            String currency,
            String reference,
            List<String> channels,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("callback_url") String callbackUrl
    ) { }

    private record CheckoutEnvelope(boolean status, String message, CheckoutData data) { }

    private record CheckoutData(
            String reference,
            @JsonProperty("authorization_url") String authorizationUrl,
            @JsonProperty("access_code") String accessCode
    ) { }

    private record VerifyEnvelope(boolean status, String message, VerifyData data) { }

    private record VerifyData(
            String status,
            String reference,
            long amount,
            String currency
    ) { }
}
