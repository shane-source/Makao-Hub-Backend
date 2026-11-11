package com.makaohub.backend.payment.service;

import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaystackPaymentProviderTest {

    @Mock private UserRepository userRepository;
    @Mock private PaystackClient paystackClient;

    @Test
    void missingPayerMeansNoExternalCheckout() {
        UUID payerId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent(payerId, PaymentType.PLAN_UPGRADE,
                null, null, PaymentTargetKind.NONE, 500, PaymentIntent.KES, "synthetic-no-phone");
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new PaystackPaymentProvider(userRepository, paystackClient).start(intent));
        assertTrue(failure.getMessage().contains("payer"));
        org.mockito.Mockito.verifyNoInteractions(paystackClient);
    }

    @Test
    void startsHostedCheckoutWithoutSendingStoredPhone() {
        UUID payerId = UUID.randomUUID();
        User payer = org.mockito.Mockito.mock(User.class);
        when(userRepository.findById(payerId)).thenReturn(Optional.of(payer));
        when(payer.getEmail()).thenReturn("renter@example.com");
        when(paystackClient.initialize(
                eq("renter@example.com"), eq(1_500L),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(new PaystackClient.CheckoutStart(
                "ps-ref-1", "https://checkout.paystack.com/test123", "test123"
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
                userRepository, paystackClient
        ).start(intent);

        assertEquals("ps-ref-1", start.providerReference());
        assertEquals("https://checkout.paystack.com/test123", start.authorizationUrl());
        assertEquals("test123", start.accessCode());
        assertTrue(start.displayText().contains("return to Makao Hub"));
        verify(paystackClient).initialize("renter@example.com", 1_500L, "MH-" + intent.getId());
    }
}
