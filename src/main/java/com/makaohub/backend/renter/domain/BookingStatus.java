package com.makaohub.backend.renter.domain;

public enum BookingStatus {
    AWAITING_PAYMENT,
    PAYMENT_PENDING,
    PAID_HELD,
    /** Both parties agreed and the mock payout has been released. */
    ACTIVE,
    CANCELLED
}
