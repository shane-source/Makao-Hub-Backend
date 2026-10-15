package com.makaohub.backend.payment.service;

import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.service.PhoneProtectionService;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.paystack.client.PaystackClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaystackPaymentProviderTest {

    @Mock private UserRepository userRepository;
    @Mock private PhoneProtectionService phoneProtectionService;
    @Mock private PaystackClient paystackClient;

    @Test
    void sendsOnlyNormalizedPhoneToPaystackAndReturnsProviderPrompt() {
        UUID payerId = UUID.randomUUID();
        User payer = org.mockito.Mockito.mock(User.class);
        byte[] ciphertext = new byte[]{1, 2, 3};
        when(userRepository.findById(payerId)).thenReturn(Optional.of(payer));
        when(payer.getEmail()).thenReturn("renter@example.com");
        when(payer.getPhoneCiphertext()).thenReturn(ciphertext);
        when(phoneProtectionService.decrypt(ciphertext)).thenReturn("0712345678");
        when(paystackClient.charge(
                eq("renter@example.com"), eq(1_500L),
                org.mockito.ArgumentMatchers.anyString(), eq("254712345678")
        )).thenReturn(new PaystackClient.ChargeStart(
                "ps-ref-1", "pay_offline", "Approve the prompt on your phone."
        ));

        PaymentIntent intent = new PaymentIntent(
                payerId,
                PaymentType.EXACT_LOCATION_UNLOCK,
                UUID.randomUUID(),
                null,
                PaymentTargetKind.LANDLORD_LISTING,
                1_500,
                PaymentIntent.KES,
                "paystack-1"
        );

        ProviderPaymentStart start = new PaystackPaymentProvider(
                userRepository, phoneProtectionService, paystackClient
        ).start(intent);

        assertEquals("ps-ref-1", start.providerReference());
        assertEquals("Approve the prompt on your phone.", start.displayText());
        verify(phoneProtectionService).decrypt(ciphertext);
    }
}
