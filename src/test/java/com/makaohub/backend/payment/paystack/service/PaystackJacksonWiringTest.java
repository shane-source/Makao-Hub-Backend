package com.makaohub.backend.payment.paystack.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Proves that Spring Boot supplies the Jackson 3 mapper required by Paystack webhook handling. */
class PaystackJacksonWiringTest {

    @Test
    void springBootProvidesPaystackWebhookJsonMapper() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(JsonMapper.class));
                });
    }
}
