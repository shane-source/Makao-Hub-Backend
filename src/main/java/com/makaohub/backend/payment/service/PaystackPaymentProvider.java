package com.makaohub.backend.payment.service;

import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.paystack.client.PaystackClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Creates a test-only hosted Paystack checkout for a server-priced payment intent. */
@Service
@Profile("paystack")
public class PaystackPaymentProvider implements PaymentProvider {

    private final UserRepository userRepository;
    private final PaystackClient paystackClient;

    public PaystackPaymentProvider(
            UserRepository userRepository,
            PaystackClient paystackClient
    ) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.paystackClient = Objects.requireNonNull(paystackClient);
    }

    @Override
    public ProviderPaymentStart start(PaymentIntent intent) {
        Objects.requireNonNull(intent, "Payment intent is required.");
        var payer = userRepository.findById(intent.getPayerUserId())
                .orElseThrow(() -> new IllegalStateException("Payment payer no longer exists."));
        PaystackClient.CheckoutStart checkout = paystackClient.initialize(
                payer.getEmail(),
                intent.getAmountMinor(),
                "MH-" + intent.getId()
        );
        return new ProviderPaymentStart(
                checkout.reference(), checkout.authorizationUrl(), checkout.accessCode(),
                "Complete the TEST payment on Paystack, then return to Makao Hub to check its status."
        );
    }
}
