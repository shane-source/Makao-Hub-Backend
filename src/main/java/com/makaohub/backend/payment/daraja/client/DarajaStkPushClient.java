package com.makaohub.backend.payment.daraja.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.makaohub.backend.auth.service.KenyanPhoneNumberNormalizer;
import com.makaohub.backend.payment.daraja.config.DarajaProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Sends one server-created STK Push request to the Daraja sandbox. */
@Service
public class DarajaStkPushClient {

    private static final DateTimeFormatter DARAJA_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId KENYA_ZONE = ZoneId.of("Africa/Nairobi");

    private final DarajaProperties properties;
    private final DarajaOAuthClient oauthClient;

    public DarajaStkPushClient(
            DarajaProperties properties,
            DarajaOAuthClient oauthClient
    ) {
        this.properties = Objects.requireNonNull(properties);
        this.oauthClient = Objects.requireNonNull(oauthClient);
    }

    public StkPushResponse push(
            String phoneNumber,
            long amount,
            String accountReference,
            String transactionDescription
    ) {
        if (!properties.enabled()) {
            throw new IllegalStateException("Daraja integration is disabled.");
        }
        if (amount <= 0 || amount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("STK amount must be a positive whole KES amount.");
        }

        String normalizedPhone = KenyanPhoneNumberNormalizer.normalize(phoneNumber);
        String partyA = normalizedPhone.substring(1);
        String shortCode = required(properties.shortCode(), "DARAJA_SHORT_CODE");
        String passkey = required(properties.passkey(), "DARAJA_PASSKEY");
        String callbackUrl = required(properties.callbackUrl(), "DARAJA_CALLBACK_URL");
        requireHttps(callbackUrl);

        String timestamp = ZonedDateTime.now(KENYA_ZONE).format(DARAJA_TIMESTAMP);
        String password = Base64.getEncoder().encodeToString(
                (shortCode + passkey + timestamp).getBytes(StandardCharsets.UTF_8)
        );

        StkPushRequest request = new StkPushRequest(
                shortCode,
                password,
                timestamp,
                "CustomerPayBillOnline",
                amount,
                partyA,
                shortCode,
                partyA,
                callbackUrl,
                required(accountReference, "accountReference"),
                required(transactionDescription, "transactionDescription")
        );

        try {
            StkPushResponse response = RestClient.builder()
                    .baseUrl(required(properties.baseUrl(), "DARAJA_BASE_URL"))
                    .build()
                    .post()
                    .uri("/mpesa/stkpush/v1/processrequest")
                    .headers(headers -> {
                        headers.setBearerAuth(oauthClient.accessToken());
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .body(request)
                    .retrieve()
                    .body(StkPushResponse.class);

            if (response == null || response.checkoutRequestId() == null
                    || response.checkoutRequestId().isBlank()) {
                throw new IllegalStateException("Daraja returned no checkout request ID.");
            }
            if (!"0".equals(response.responseCode())) {
                throw new IllegalStateException("Daraja rejected the STK Push request.");
            }
            return response;
        } catch (RestClientResponseException exception) {
            // Never return the provider body: it can include sensitive request data.
            throw new IllegalStateException("Daraja STK Push request failed.", exception);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not configured.");
        }
        return value.strip();
    }

    private static void requireHttps(String callbackUrl) {
        if (!callbackUrl.startsWith("https://")) {
            throw new IllegalStateException("DARAJA_CALLBACK_URL must use HTTPS.");
        }
    }

    private record StkPushRequest(
            @JsonProperty("BusinessShortCode") String businessShortCode,
            @JsonProperty("Password") String password,
            @JsonProperty("Timestamp") String timestamp,
            @JsonProperty("TransactionType") String transactionType,
            @JsonProperty("Amount") long amount,
            @JsonProperty("PartyA") String partyA,
            @JsonProperty("PartyB") String partyB,
            @JsonProperty("PhoneNumber") String phoneNumber,
            @JsonProperty("CallBackURL") String callbackUrl,
            @JsonProperty("AccountReference") String accountReference,
            @JsonProperty("TransactionDesc") String transactionDescription
    ) {
    }

    public record StkPushResponse(
            @JsonProperty("MerchantRequestID") String merchantRequestId,
            @JsonProperty("CheckoutRequestID") String checkoutRequestId,
            @JsonProperty("ResponseCode") String responseCode,
            @JsonProperty("ResponseDescription") String responseDescription,
            @JsonProperty("CustomerMessage") String customerMessage
    ) {
    }
}
