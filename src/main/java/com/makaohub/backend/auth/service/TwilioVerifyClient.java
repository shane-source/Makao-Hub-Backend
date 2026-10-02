package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.config.TwilioVerifyProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class TwilioVerifyClient {

    private final RestClient restClient;
    private final TwilioVerifyProperties properties;

    public TwilioVerifyClient(
            TwilioVerifyProperties properties
    ) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl("https://verify.twilio.com/v2")
                .defaultHeaders(headers -> headers.setBasicAuth(
                        properties.apiKeySid(),
                        properties.apiKeySecret()
                ))
                .build();
    }

    public void startSmsVerification(String phoneNumber) {
        MultiValueMap<String, String> form =
                new LinkedMultiValueMap<>();

        form.add("To", phoneNumber);
        form.add("Channel", "sms");

        VerificationResponse response = restClient.post()
                .uri(
                        "/Services/"
                                + properties.serviceSid()
                                + "/Verifications"
                )
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(VerificationResponse.class);

        if (response == null || !"pending".equals(response.status())) {
            throw new IllegalStateException(
                    "Twilio did not start phone verification."
            );
        }
    }

    public boolean isCodeApproved(
            String phoneNumber,
            String code
    ) {
        MultiValueMap<String, String> form =
                new LinkedMultiValueMap<>();

        form.add("To", phoneNumber);
        form.add("Code", code);

        try {
            VerificationResponse response = restClient.post()
                    .uri(
                            "/Services/"
                                    + properties.serviceSid()
                                    + "/VerificationCheck"
                    )
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(VerificationResponse.class);

            return response != null
                    && "approved".equals(response.status());
        } catch (RestClientResponseException exception) {
            return false;
        }
    }

    private record VerificationResponse(String status) {
    }
}