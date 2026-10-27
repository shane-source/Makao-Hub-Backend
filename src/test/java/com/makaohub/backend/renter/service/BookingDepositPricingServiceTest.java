package com.makaohub.backend.renter.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class BookingDepositPricingServiceTest {

    private static final String DEPOSIT_KEY = "makao.payment.prices.booking-deposit-minor";

    @Test
    void mockProfileUsesIllustrativeOneMonthDepositWhenNoFallbackIsConfigured() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mock");
        environment.setProperty(DEPOSIT_KEY, "");

        assertEquals(4_500_000L,
                new BookingDepositPricingService(environment).depositAmountMinor(45_000L));
    }

    @Test
    void liveProfileStillRequiresExplicitFallbackPricing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(DEPOSIT_KEY, "");

        assertThrows(ResponseStatusException.class,
                () -> new BookingDepositPricingService(environment).depositAmountMinor(45_000L));
    }
}
