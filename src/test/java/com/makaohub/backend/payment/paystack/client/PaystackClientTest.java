package com.makaohub.backend.payment.paystack.client;

import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.payment.paystack.config.PaystackProperties;
import com.makaohub.backend.payment.service.PaystackPaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaystackClientTest {

    @Test
    void springSelectsTheProductionConstructorWithoutStartingACharge() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("paystack");
            context.registerBean(PaystackProperties.class,
                    () -> new PaystackProperties(true, "https://api.paystack.co", "sk_test_synthetic"));
            context.registerBean(UserRepository.class,
                    () -> org.mockito.Mockito.mock(UserRepository.class));
            context.register(PaystackClient.class, PaystackPaymentProvider.class);
            context.refresh();
            assertNotNull(context.getBean(PaystackClient.class));
            assertNotNull(context.getBean(PaystackPaymentProvider.class));
        }
    }

    @Test
    void liveKeyCannotStartTestOnlyCheckout() {
        PaystackClient client = new PaystackClient(new PaystackProperties(
                true, "https://api.paystack.co", "sk_live_not_a_real_key"
        ));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.initialize("renter@example.test", 8_400_000L,
                        "test-booking"));

        assertTrue(error.getMessage().contains("test secret key"));
    }

    @Test
    void initializesMobileMoneyCheckoutWithServerAmountAndReference() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.paystack.co");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaystackClient client = new PaystackClient(new PaystackProperties(
                true, "https://api.paystack.co", "sk_test_synthetic", "https://api.example.invalid/payments/paystack/return"
        ), builder.build());
        server.expect(requestTo("https://api.paystack.co/transaction/initialize"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk_test_synthetic"))
                .andExpect(content().json("""
                        {"email":"renter@example.test","amount":"8400000","currency":"KES",
                         "reference":"MH-test-booking","channels":["mobile_money"],
                         "callback_url":"https://api.example.invalid/payments/paystack/return"}
                        """))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Authorization URL created","data":{
                         "authorization_url":"https://checkout.paystack.com/test123",
                         "access_code":"test123","reference":"MH-test-booking"}}
                        """, MediaType.APPLICATION_JSON));

        PaystackClient.CheckoutStart checkout = client.initialize("renter@example.test", 8_400_000L, "MH-test-booking");
        assertEquals("https://checkout.paystack.com/test123", checkout.authorizationUrl());
        assertEquals("MH-test-booking", checkout.reference());
        server.verify();
    }

    @Test
    void rejectsCheckoutLinkOutsidePaystack() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.paystack.co");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaystackClient client = new PaystackClient(new PaystackProperties(
                true, "https://api.paystack.co", "sk_test_synthetic"
        ), builder.build());
        server.expect(requestTo("https://api.paystack.co/transaction/initialize"))
                .andExpect(content().json("""
                        {"email":"renter@example.test","amount":"8400000","currency":"KES",
                         "reference":"MH-test-booking","channels":["mobile_money"]}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"status":true,"data":{"authorization_url":"https://checkout.paystack.com.attacker.test/steal",
                        "access_code":"test123","reference":"MH-test-booking"}}
                        """, MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class,
                () -> client.initialize("renter@example.test", 8_400_000L, "MH-test-booking"));
        server.verify();
    }
}
