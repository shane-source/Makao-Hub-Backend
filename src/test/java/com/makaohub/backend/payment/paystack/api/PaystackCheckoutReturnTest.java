package com.makaohub.backend.payment.paystack.api;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaystackCheckoutReturnController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("paystack")
class PaystackCheckoutReturnTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UploadSecurityProperties uploads;

    @Test void publicReturnPageHasAnExplicitAppButtonButNeverClaimsPaymentSuccess() throws Exception {
        mvc.perform(get("/payments/paystack/return"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(content().string(containsString("Return to Makao Hub</a>")))
                .andExpect(content().string(containsString("intent://payments/return#Intent;scheme=makaohub;package=com.makaohub.android;end")))
                .andExpect(content().string(containsString("This page does not confirm payment")))
                .andExpect(content().string(not(containsString("<script"))));
    }

    @Test void providerReferencesAndForgedStatusesAreStrippedNotReflectedOrForwarded() throws Exception {
        mvc.perform(get("/payments/paystack/return")
                        .queryParam("reference", "synthetic-private-reference")
                        .queryParam("status", "success")
                        .queryParam("next", "https://attacker.example.invalid/"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/payments/paystack/return"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(""));
    }

    @Test void staticReturnExceptionDoesNotExposeProtectedPaymentApisOrPostRequests() throws Exception {
        mvc.perform(post("/payments/paystack/return")).andExpect(status().isUnauthorized());
        mvc.perform(get("/payments/paystack/return/other")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/payments")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/paystack/reconcile")).andExpect(status().isUnauthorized());
    }
}
