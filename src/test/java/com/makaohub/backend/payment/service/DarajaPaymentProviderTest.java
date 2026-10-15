package com.makaohub.backend.payment.service;

import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.service.PhoneProtectionService;
import com.makaohub.backend.payment.daraja.client.DarajaStkPushClient;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.domain.PaymentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DarajaPaymentProviderTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PhoneProtectionService phoneProtectionService;

    @Mock
    private DarajaStkPushClient stkPushClient;

    @Test
    void decryptsThePayerPhoneOnlyToStartTheProviderRequest() {
        UUID payerId = UUID.randomUUID();
        byte[] protectedPhone = new byte[]{1, 2, 3};
        User payer = org.mockito.Mockito.mock(User.class);
        when(userRepository.findById(payerId)).thenReturn(Optional.of(payer));
        when(payer.getPhoneCiphertext()).thenReturn(protectedPhone);
        when(phoneProtectionService.decrypt(protectedPhone))
                .thenReturn("+254712345678");
        when(stkPushClient.push(
                eq("+254712345678"),
                eq(200L),
                any(String.class),
                eq(PaymentType.EXACT_LOCATION_UNLOCK.name())
        )).thenReturn(new DarajaStkPushClient.StkPushResponse(
                "merchant", "checkout", "0", "accepted", "prompted"
        ));

        PaymentIntent intent = new PaymentIntent(
                payerId,
                PaymentType.EXACT_LOCATION_UNLOCK,
                UUID.randomUUID(),
                null,
                PaymentTargetKind.LANDLORD_LISTING,
                200,
                PaymentIntent.KES,
                "unlock-1"
        );

        ProviderPaymentStart providerStart = new DarajaPaymentProvider(
                userRepository,
                phoneProtectionService,
                stkPushClient
        ).start(intent);

        assertEquals("checkout", providerStart.providerReference());
        verify(phoneProtectionService).decrypt(protectedPhone);
        verify(stkPushClient).push(
                eq("+254712345678"),
                eq(200L),
                any(String.class),
                eq(PaymentType.EXACT_LOCATION_UNLOCK.name())
        );
    }

    @Test
    void refusesToStartWhenThePayerAccountNoLongerExists() {
        UUID payerId = UUID.randomUUID();
        when(userRepository.findById(payerId)).thenReturn(Optional.empty());

        PaymentIntent intent = new PaymentIntent(
                payerId,
                PaymentType.PLAN_UPGRADE,
                null,
                null,
                PaymentTargetKind.NONE,
                499,
                PaymentIntent.KES,
                "plan-1"
        );

        DarajaPaymentProvider provider = new DarajaPaymentProvider(
                userRepository,
                phoneProtectionService,
                stkPushClient
        );

        assertThrows(IllegalStateException.class, () -> provider.start(intent));
    }
}
