package com.makaohub.backend.payment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentIntentTest {

    @Test
    void paymentRequiresPendingBeforeSuccess() {
        PaymentIntent intent = new PaymentIntent(
                UUID.randomUUID(),
                PaymentType.EXACT_LOCATION_UNLOCK,
                UUID.randomUUID(),
                null,
                PaymentTargetKind.LANDLORD_LISTING,
                200,
                "kes",
                "unlock-1"
        );

        assertThrows(
                IllegalStateException.class,
                () -> intent.markSucceeded(Instant.now())
        );

        intent.markPending("mock-payment-1");
        intent.markSucceeded(Instant.now());

        assertEquals(PaymentStatus.SUCCEEDED, intent.getStatus());
    }

    @Test
    void bookingCannotAlsoTargetAListing() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaymentIntent(
                        UUID.randomUUID(),
                        PaymentType.BOOKING,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PaymentTargetKind.BOOKING,
                        50_000,
                        "KES",
                        "booking-1"
                )
        );
    }
}
