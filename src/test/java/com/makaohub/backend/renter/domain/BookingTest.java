package com.makaohub.backend.renter.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        booking.confirmOwnerMoveIn(Instant.EPOCH);
        booking.respondToOwnerMoveIn(true, Instant.EPOCH, "fresh-test-response");
        booking.activate();

        assertEquals(BookingStatus.ACTIVE, booking.getStatus());
        assertEquals(15_000, booking.getTotalAmountMinor());
        assertEquals(Instant.EPOCH.plus(5, ChronoUnit.MINUTES), booking.getReleaseEligibleAt());
    }

    @Test
    void secondMoveInConfirmationStartsFiveMinuteReleaseWindow() {
        Booking booking = booking();
        booking.beginPayment();
        booking.markPaidHeld();
        booking.confirmRenterAgreement(Instant.EPOCH);
        booking.confirmOwnerAgreement(Instant.EPOCH);

        booking.confirmMoveIn(Instant.EPOCH);

        assertEquals(Instant.EPOCH, booking.getMoveInConfirmedAt());
        assertNull(booking.getReleaseEligibleAt());
        assertEquals(false, booking.isReleaseEligible(Instant.EPOCH.plus(24, ChronoUnit.HOURS)));
        booking.confirmOwnerMoveIn(Instant.EPOCH.plus(1, ChronoUnit.HOURS));
        booking.respondToOwnerMoveIn(true, Instant.EPOCH.plus(1, ChronoUnit.HOURS), "fresh-test-response");
        Instant deadline = Instant.EPOCH.plus(65, ChronoUnit.MINUTES);
        assertEquals(deadline, booking.getReleaseEligibleAt());
        assertEquals(false, booking.isReleaseEligible(deadline.minusSeconds(1)));
        assertEquals(true, booking.isReleaseEligible(deadline));
        assertEquals(false, booking.confirmOwnerMoveIn(deadline));
        booking.confirmMoveIn(deadline);
        assertEquals(deadline, booking.getReleaseEligibleAt());
    }

    @Test
    void ownerCannotConfirmMoveInBeforeRenterAndCannotShortenHold() {
        Booking booking = booking();
        booking.beginPayment();
        booking.markPaidHeld();
        booking.confirmRenterAgreement(Instant.EPOCH);
        booking.confirmOwnerAgreement(Instant.EPOCH);
        assertThrows(IllegalStateException.class, () -> booking.confirmOwnerMoveIn(Instant.EPOCH));
        booking.confirmMoveIn(Instant.EPOCH);
        assertThrows(IllegalStateException.class, () -> booking.requestReleaseReview(Instant.EPOCH));
        assertThrows(IllegalStateException.class, () -> booking.markReleased(
                Instant.EPOCH.plus(24, ChronoUnit.HOURS), "test-payout", "test-release"));
        booking.confirmOwnerMoveIn(Instant.EPOCH.plus(2, ChronoUnit.HOURS));
        booking.respondToOwnerMoveIn(true, Instant.EPOCH.plus(2, ChronoUnit.HOURS), "fresh-test-response");
        Instant deadline = Instant.EPOCH.plus(125, ChronoUnit.MINUTES);
        assertEquals(deadline, booking.getReleaseEligibleAt());
        assertThrows(IllegalStateException.class, () -> booking.markReleased(
                deadline.minusSeconds(1), "test-payout", "test-release"));
        assertNull(booking.getReleasedAt());
        booking.markReleased(deadline, "test-payout", "test-release");
        assertEquals(BookingStatus.ACTIVE, booking.getStatus());
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
