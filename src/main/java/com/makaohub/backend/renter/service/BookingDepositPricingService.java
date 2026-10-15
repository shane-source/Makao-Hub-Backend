package com.makaohub.backend.renter.service;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Reads the booking deposit from server configuration, never from a client. */
@Service
public class BookingDepositPricingService {

    private static final String DEPOSIT_KEY =
            "makao.payment.prices.booking-deposit-minor";

    private final Environment environment;

    public BookingDepositPricingService(Environment environment) {
        this.environment = environment;
    }

    public long depositAmountMinor() {
        String raw = environment.getProperty(DEPOSIT_KEY);
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Booking deposit pricing is not configured."
            );
        }
        try {
            long amount = Long.parseLong(raw.strip());
            if (amount <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Booking deposit pricing is invalid."
            );
        }
    }
}
