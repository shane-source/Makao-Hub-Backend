package com.makaohub.backend.payment.service;

import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.service.PhoneProtectionService;
import com.makaohub.backend.payment.daraja.client.DarajaStkPushClient;
import com.makaohub.backend.payment.domain.PaymentIntent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Starts a real Daraja STK Push using the payer's server-protected phone. */
@Service
@Profile("daraja")
public class DarajaPaymentProvider implements PaymentProvider {

    private final UserRepository userRepository;
    private final PhoneProtectionService phoneProtectionService;
    private final DarajaStkPushClient stkPushClient;

    public DarajaPaymentProvider(
            UserRepository userRepository,
            PhoneProtectionService phoneProtectionService,
            DarajaStkPushClient stkPushClient
    ) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.phoneProtectionService = Objects.requireNonNull(phoneProtectionService);
        this.stkPushClient = Objects.requireNonNull(stkPushClient);
    }

    @Override
    public ProviderPaymentStart start(PaymentIntent intent) {
        Objects.requireNonNull(intent, "Payment intent is required.");

        var payer = userRepository.findById(intent.getPayerUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment payer no longer exists."
                ));

        // Decryption is kept inside this provider boundary. The plaintext phone
        // is passed directly to Daraja and is never persisted or logged here.
        String phoneNumber = phoneProtectionService.decrypt(
                payer.getPhoneCiphertext()
        );

        String accountReference = "MH-"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12);

        var response = stkPushClient.push(
                phoneNumber,
                intent.getAmountMinor(),
                accountReference,
                intent.getPaymentType().name()
        );

        return new ProviderPaymentStart(response.checkoutRequestId(), null, null);
    }
}
