package com.makaohub.backend.payment.service;

import com.makaohub.backend.payment.domain.PaymentIntent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Test-only provider; it never moves money. */
@Service
@Profile("mock")
public class MockPaymentProvider implements PaymentProvider {

    @Override
    public ProviderPaymentStart start(PaymentIntent intent) {
        Objects.requireNonNull(intent, "Payment intent is required.");
        return new ProviderPaymentStart("mock-" + UUID.randomUUID(), null, null);
    }
}
