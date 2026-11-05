package com.makaohub.backend.payment.paystack.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PaystackJackson2ConfigTest {
    @Test
    void exposesMapperForPaystackProfile() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("paystack"))
                .withUserConfiguration(PaystackJackson2Config.class)
                .run(context -> assertThat(context).hasSingleBean(ObjectMapper.class));
    }
}
