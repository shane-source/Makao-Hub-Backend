package com.makaohub.backend.payment.daraja.client;

import com.fasterxml.jackson.annotation.JsonProperty;
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

/** Queries Daraja after a callback or timeout before settling local money state. */
@Service
public class DarajaStkQueryClient {

    private static final DateTimeFormatter DARAJA_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId KENYA_ZONE = ZoneId.of("Africa/Nairobi");

    private final DarajaProperties properties;
    private final DarajaOAuthClient oauthClient;

    public DarajaStkQueryClient(
            DarajaProperties properties,
            DarajaOAuthClient oauthClient
    ) {
        this.properties = Objects.requireNonNull(properties);
        this.oauthClient = Objects.requireNonNull(oauthClient);
    }

    public StkQueryResponse query(String checkoutRequestId) {
        if (!properties.enabled()) {
            throw new IllegalStateException("Daraja integration is disabled.");
        }

        String checkoutId = required(checkoutRequestId, "CheckoutRequestID");
        String shortCode = required(properties.shortCode(), "DARAJA_SHORT_CODE");
        String passkey = required(properties.passkey(), "DARAJA_PASSKEY");
        String timestamp = ZonedDateTime.now(KENYA_ZONE).format(DARAJA_TIMESTAMP);
        String password = Base64.getEncoder().encodeToString(
                (shortCode + passkey + timestamp).getBytes(StandardCharsets.UTF_8)
        );

        StkQueryRequest request = new StkQueryRequest(
                shortCode,
                password,
                timestamp,
                checkoutId
        );

        try {
            StkQueryResponse response = RestClient.builder()
                    .baseUrl(required(properties.baseUrl(), "DARAJA_BASE_URL"))
                    .build()
                    .post()
                    .uri("/mpesa/stkpushquery/v1/query")
                    .headers(headers -> {
                        headers.setBearerAuth(oauthClient.accessToken());
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .body(request)
                    .retrieve()
                    .body(StkQueryResponse.class);

            if (response == null || response.resultCode() == null) {
                throw new IllegalStateException("Daraja returned no transaction result.");
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("Daraja transaction query failed.", exception);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not configured.");
        }
        return value.strip();
    }

    private record StkQueryRequest(
            @JsonProperty("BusinessShortCode") String businessShortCode,
            @JsonProperty("Password") String password,
            @JsonProperty("Timestamp") String timestamp,
            @JsonProperty("CheckoutRequestID") String checkoutRequestId
    ) {
    }

    public record StkQueryResponse(
            @JsonProperty("ResponseCode") Integer responseCode,
            @JsonProperty("ResponseDescription") String responseDescription,
            @JsonProperty("MerchantRequestID") String merchantRequestId,
            @JsonProperty("CheckoutRequestID") String checkoutRequestId,
            @JsonProperty("ResultCode") Integer resultCode,
            @JsonProperty("ResultDesc") String resultDescription
    ) {
    }
}
