package com.makaohub.backend.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** One-time startup report of which settings are present. It prints SET/MISSING only, never values. */
@Component
public class StartupConfigReport {

    private static final Logger log = LoggerFactory.getLogger("makao.config");

    private final Environment env;

    public StartupConfigReport(Environment env) { this.env = env; }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        log.info("CONFIG profiles={}", Arrays.toString(env.getActiveProfiles()));
        String key = env.getProperty("makao.paystack.secret-key", "");
        log.info("CONFIG paystack.enabled={} secretKey={} baseUrl={} returnUrl={}",
                env.getProperty("makao.paystack.enabled", "false"),
                key.isBlank() ? "MISSING" : key.startsWith("sk_test_") ? "SET(test)" : "SET(NOT A TEST KEY - will be rejected)",
                presence("makao.paystack.base-url"), presence("makao.paystack.checkout-return-url"));
        List<String> others = List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password");
        others.forEach(name -> log.info("CONFIG {}={}", name, presence(name)));
        if (!"true".equalsIgnoreCase(env.getProperty("makao.paystack.enabled", "false"))
                || key.isBlank()) {
            log.warn("CONFIG Paystack is NOT ready: payments will report 'unavailable' until enabled and keyed.");
        }
    }

    private String presence(String name) {
        String value = env.getProperty(name);
        return value == null || value.isBlank() ? "MISSING" : "SET";
    }
}
