package com.makaohub.backend.payment.paystack.client;

import com.makaohub.backend.payment.paystack.config.PaystackProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class PaystackReturnConfigurationTest {
    @Test void acceptsOwnedHttpsReturnPageAndAllowsUnconfiguredExistingCheckout() {
        String url = "https://api.example.invalid/payments/paystack/return";
        assertEquals(url, new PaystackProperties(true, "https://api.paystack.co", "sk_test_synthetic", url).validatedCheckoutReturnUrl());
        assertNull(new PaystackProperties(true, "https://api.paystack.co", "sk_test_synthetic").validatedCheckoutReturnUrl());
        assertNull(new PaystackProperties(true, "https://api.paystack.co", "sk_test_synthetic", "").validatedCheckoutReturnUrl());
        assertFalse(new PaystackProperties(true, "https://api.paystack.co", "sk_test_synthetic").toString().contains("sk_test_"));
    }

    @Test void rejectsExternalCleartextCredentialsQueryDataFragmentsAndWrongEndpoints() {
        for (String url : new String[]{"http://127.0.0.1:8080/payments/paystack/return",
                "https://localhost/payments/paystack/return", "https://127.0.0.1/payments/paystack/return",
                "https://app.localhost/payments/paystack/return", "https://[::1]/payments/paystack/return",
                "http://example.invalid/payments/paystack/return",
                "http://127.0.0.1.attacker.invalid:8080/payments/paystack/return",
                "https://user@example.invalid/payments/paystack/return",
                "https://api.example.invalid/payments/paystack/return?reference=private",
                "https://api.example.invalid/payments/paystack/return#private",
                "https://api.example.invalid/other", "makaohub://payments/return"}) {
            assertThrows(IllegalStateException.class,
                    () -> new PaystackProperties(true, "https://api.paystack.co", "sk_test_synthetic", url).validatedCheckoutReturnUrl());
        }
    }

    @Test void springBindsTheConfiguredCallbackUsingTheCanonicalConstructor() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("makao.paystack.enabled=true", "makao.paystack.base-url=https://api.paystack.co",
                        "makao.paystack.secret-key=sk_test_synthetic",
                        "makao.paystack.checkout-return-url=https://api.example.invalid/payments/paystack/return")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("https://api.example.invalid/payments/paystack/return",
                            context.getBean(PaystackProperties.class).validatedCheckoutReturnUrl());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaystackProperties.class)
    static class PropertiesConfiguration { }
}
