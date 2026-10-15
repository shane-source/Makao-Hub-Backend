package com.makaohub.backend.renter.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookingTest {

    @Test
    void bookingRequiresPaymentBeforeItCanBeActivated() {
        Booking booking = booking();

        assertThrows(IllegalStateException.class, booking::activate);
        booking.beginPayment();
        booking.markPaidHeld();
        booking.confirmRenterAgreement(java.time.Instant.EPOCH);
        booking.confirmOwnerAgreement(java.time.Instant.EPOCH);
        booking.confirmMoveIn(Instant.EPOCH);
        booking.activate();

        assertEquals(BookingStatus.ACTIVE, booking.getStatus());
        assertEquals(15_000, booking.getTotalAmountMinor());
        assertEquals(Instant.EPOCH.plus(24, ChronoUnit.HOURS), booking.getReleaseEligibleAt());
    }

    @Test
    void moveInStartsA24HourReleaseWindow() {
        Booking booking = booking();
        booking.beginPayment();
        booking.markPaidHeld();
        booking.confirmRenterAgreement(Instant.EPOCH);
        booking.confirmOwnerAgreement(Instant.EPOCH);

        booking.confirmMoveIn(Instant.EPOCH);

        assertEquals(Instant.EPOCH, booking.getMoveInConfirmedAt());
        assertEquals(Instant.EPOCH.plus(24, ChronoUnit.HOURS), booking.getReleaseEligibleAt());
        assertEquals(false, booking.isReleaseEligible(Instant.EPOCH.plus(23, ChronoUnit.HOURS)));
        assertEquals(true, booking.isReleaseEligible(Instant.EPOCH.plus(24, ChronoUnit.HOURS)));
    }

    @Test
    void paidHeldBookingCannotBeCancelled() {
        Booking booking = booking();
        booking.beginPayment();

        assertThrows(IllegalStateException.class, booking::cancel);
    }

    private static Booking booking() {
        return new Booking(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BookingListingKind.LANDLORD_LISTING, UUID.randomUUID(),
                10_000, 5_000, "KES"
        );
    }
}
