package com.makaohub.backend.renter.service;

import org.springframework.stereotype.Service;

/** Calculates the fixed Makao Hub commission for a booking snapshot. */
@Service
public class BookingCommissionService {

    private static final long COMMISSION_PERCENT = 5;
    private static final long PERCENT_DENOMINATOR = 100;

    /** Calculates 5% of rent plus deposit, rounded up to a whole KES amount. */
    public long commissionAmountMinor(long rentAmountMinor, long depositAmountMinor) {
        if (rentAmountMinor <= 0 || depositAmountMinor <= 0) {
            throw new IllegalArgumentException(
                    "Rent and deposit must be positive before commission is calculated."
            );
        }
        long base = Math.addExact(rentAmountMinor, depositAmountMinor);
        long whole = base / PERCENT_DENOMINATOR * COMMISSION_PERCENT;
        long remainder = base % PERCENT_DENOMINATOR * COMMISSION_PERCENT;
        long rounded = Math.addExact(
                whole,
                remainder == 0
                        ? 0
                        : (remainder + PERCENT_DENOMINATOR - 1)
                        / PERCENT_DENOMINATOR
        );
        return Math.max(1, rounded);
    }
}
