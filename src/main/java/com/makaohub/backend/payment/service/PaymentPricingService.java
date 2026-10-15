package com.makaohub.backend.payment.service;

import com.makaohub.backend.payment.domain.PaymentType;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Reads server-owned test prices; clients never submit payment amounts. */
@Service
public class PaymentPricingService {

    private static final Map<PaymentType, String> KEYS = Map.of(
            PaymentType.EXACT_LOCATION_UNLOCK,
            "makao.payment.prices.exact-location-unlock-minor",
            PaymentType.VIDEO_UNLOCK,
            "makao.payment.prices.video-unlock-minor",
            PaymentType.PHONE_UNLOCK,
            "makao.payment.prices.phone-unlock-minor",
            PaymentType.PLAN_UPGRADE,
            "makao.payment.prices.plan-upgrade-minor",
            PaymentType.BOOST,
            "makao.payment.prices.boost-minor",
            PaymentType.LISTING_ADD_ON,
            "makao.payment.prices.listing-add-on-minor"
    );

    private final Environment environment;

    public PaymentPricingService(Environment environment) {
        this.environment = environment;
    }

    public long amountMinor(PaymentType type) {
        String key = KEYS.get(type);
        if (key == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This payment type is not available in the mock flow."
            );
        }

        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment pricing is not configured."
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
                    "Payment pricing is invalid."
            );
        }
    }
}
