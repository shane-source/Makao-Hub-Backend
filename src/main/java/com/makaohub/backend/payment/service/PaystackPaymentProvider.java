package com.makaohub.backend.payment.service;

import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.service.KenyanPhoneNumberNormalizer;
import com.makaohub.backend.auth.service.PhoneProtectionService;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.paystack.client.PaystackClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/** Starts Paystack's Kenya M-Pesa mobile-money charge for a payment intent. */
@Service
@Profile("paystack")
public class PaystackPaymentProvider implements PaymentProvider {

    private final UserRepository userRepository;
    private final PhoneProtectionService phoneProtectionService;
    private final PaystackClient paystackClient;

    public PaystackPaymentProvider(
            UserRepository userRepository,
            PhoneProtectionService phoneProtectionService,
            PaystackClient paystackClient
    ) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.phoneProtectionService = Objects.requireNonNull(phoneProtectionService);
        this.paystackClient = Objects.requireNonNull(paystackClient);
    }

    @Override
    public ProviderPaymentStart start(PaymentIntent intent) {
        Objects.requireNonNull(intent, "Payment intent is required.");
        var payer = userRepository.findById(intent.getPayerUserId())
                .orElseThrow(() -> new IllegalStateException("Payment payer no longer exists."));
        byte[] encryptedPhone = payer.getPhoneCiphertext();
        if (encryptedPhone == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A payment phone number is required before starting M-Pesa checkout.");
        }
        String phone = KenyanPhoneNumberNormalizer.normalize(phoneProtectionService.decrypt(encryptedPhone));
        PaystackClient.ChargeStart charge = paystackClient.charge(
                payer.getEmail(),
                intent.getAmountMinor(),
                "MH-" + intent.getId(),
                phone.substring(1)
        );
        return new ProviderPaymentStart(
                charge.reference(), null, null, charge.displayText()
        );
    }
}
