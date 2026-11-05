package com.makaohub.backend.payment.paystack.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Supplies the Jackson 2 mapper required by the existing Paystack webhook handler. */
@Configuration(proxyBeanMethods = false)
@Profile("paystack")
public class PaystackJackson2Config {
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper paystackJackson2Mapper() {
        return new ObjectMapper();
    }
}
