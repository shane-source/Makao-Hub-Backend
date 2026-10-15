package com.makaohub.backend.payment.service;

import com.makaohub.backend.payment.domain.PaymentType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentPricingServiceTest {

    @Test
    void readsServerOwnedPriceAndRejectsMissingPrice() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "makao.payment.prices.exact-location-unlock-minor",
                        "200"
                );

        PaymentPricingService service = new PaymentPricingService(environment);

        assertEquals(
                200,
                service.amountMinor(PaymentType.EXACT_LOCATION_UNLOCK)
        );
        assertThrows(
                ResponseStatusException.class,
                () -> service.amountMinor(PaymentType.PLAN_UPGRADE)
        );
    }
}
