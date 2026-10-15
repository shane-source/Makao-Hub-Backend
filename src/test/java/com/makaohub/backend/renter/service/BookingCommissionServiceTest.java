package com.makaohub.backend.renter.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookingCommissionServiceTest {

    private final BookingCommissionService service = new BookingCommissionService();

    @Test
    void chargesFivePercentOfRentPlusDepositAndRoundsUp() {
        assertEquals(60_250, service.commissionAmountMinor(1_200_000, 5_000));
        assertEquals(1, service.commissionAmountMinor(1, 1));
    }

    @Test
    void rejectsNonPositiveBookingComponents() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.commissionAmountMinor(0, 5_000)
        );
    }
}
