package com.makaohub.backend.payment.service;

import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.payment.entitlement.service.ListingUnlockEntitlementService;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.verification.service.RoleVerificationGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentIntentServiceTest {

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @Mock
    private PaymentProvider paymentProvider;

    @Mock
    private MockPaymentAuditService auditService;

    @Mock
    private ListingUnlockEntitlementService entitlementService;

    @Mock
    private RoleVerificationGuard verificationGuard;

    @Test
    void createsPendingIntentAndAcceptsSuccessfulMockCallback() {
        UUID payer = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        PaymentIntentService service = new PaymentIntentService(
                paymentIntentRepository, paymentProvider, auditService,
                entitlementService, verificationGuard
        );
        var command = new PaymentIntentService.CreatePaymentCommand(
                payer, PaymentType.EXACT_LOCATION_UNLOCK, listing, null,
                PaymentTargetKind.LANDLORD_LISTING,
                200, "KES", "unlock-1"
        );

        when(paymentIntentRepository.findByPayerUserIdAndIdempotencyKey(payer, "unlock-1"))
                .thenReturn(Optional.empty());
        when(paymentIntentRepository.save(any(PaymentIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentProvider.start(any(PaymentIntent.class)))
                .thenReturn(new ProviderPaymentStart("mock-payment-1", null, null));

        PaymentIntent created = service.create(command);
        assertEquals(PaymentStatus.PENDING, created.getStatus());
        assertEquals("mock-payment-1", created.getProviderReference());
        org.mockito.Mockito.verify(verificationGuard).requireVerified(
                payer, com.makaohub.backend.auth.domain.RoleName.RENTER
        );

        when(paymentIntentRepository.findByPayerUserIdAndProviderReferenceForUpdate(
                payer,
                "mock-payment-1"
        ))
                .thenReturn(Optional.of(created));
        PaymentIntent completed = service.completeMockCallback(
                payer,
                "mock-payment-1",
                MockPaymentOutcome.SUCCESS,
                Instant.now()
        );
        assertEquals(PaymentStatus.SUCCEEDED, completed.getStatus());
        // Create saves the initial row and the provider reference, then the
        // callback saves the terminal result.
        verify(paymentIntentRepository, times(3)).save(created);
    }

    @Test
    void rejectsReuseOfIdempotencyKeyWithDifferentAmount() {
        UUID payer = UUID.randomUUID();
        PaymentIntent existing = new PaymentIntent(
                payer, PaymentType.PLAN_UPGRADE, null, null,
                PaymentTargetKind.NONE,
                499, "KES", "plan-1"
        );
        PaymentIntentService service = new PaymentIntentService(
                paymentIntentRepository, paymentProvider, auditService,
                entitlementService, verificationGuard
        );
        when(paymentIntentRepository.findByPayerUserIdAndIdempotencyKey(payer, "plan-1"))
                .thenReturn(Optional.of(existing));

        assertThrows(ResponseStatusException.class, () -> service.create(
                new PaymentIntentService.CreatePaymentCommand(
                        payer, PaymentType.PLAN_UPGRADE, null, null,
                        PaymentTargetKind.NONE,
                        1499, "KES", "plan-1"
                )
        ));
        verify(paymentProvider, never()).start(any(PaymentIntent.class));
    }

    @Test
    void retriesProviderStartForAnUnstartedIntentWithTheSameIdempotencyKey() {
        UUID payer = UUID.randomUUID();
        PaymentIntent existing = new PaymentIntent(
                payer, PaymentType.PLAN_UPGRADE, null, null,
                PaymentTargetKind.NONE,
                499, "KES", "plan-retry"
        );
        PaymentIntentService service = new PaymentIntentService(
                paymentIntentRepository, paymentProvider, auditService,
                entitlementService, verificationGuard
        );
        when(paymentIntentRepository.findByPayerUserIdAndIdempotencyKey(
                payer, "plan-retry"
        )).thenReturn(Optional.of(existing));
        when(paymentProvider.start(existing)).thenReturn(
                new ProviderPaymentStart("paystack-retry", null, null)
        );
        when(paymentIntentRepository.save(existing)).thenReturn(existing);

        PaymentIntent retried = service.create(
                new PaymentIntentService.CreatePaymentCommand(
                        payer, PaymentType.PLAN_UPGRADE, null, null,
                        PaymentTargetKind.NONE,
                        499, "KES", "plan-retry"
                )
        );

        assertEquals(PaymentStatus.PENDING, retried.getStatus());
        assertEquals("paystack-retry", retried.getProviderReference());
        verify(paymentProvider).start(existing);
        verify(paymentIntentRepository).save(existing);
    }
}
