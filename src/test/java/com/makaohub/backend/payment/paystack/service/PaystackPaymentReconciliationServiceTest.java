package com.makaohub.backend.payment.paystack.service;

import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.entitlement.service.ListingUnlockEntitlementService;
import com.makaohub.backend.payment.ledger.service.MockEscrowLedgerService;
import com.makaohub.backend.payment.paystack.client.PaystackClient;
import com.makaohub.backend.payment.paystack.config.PaystackProperties;
import com.makaohub.backend.payment.paystack.repository.PaystackWebhookReceiptRepository;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingStatus;
import com.makaohub.backend.renter.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class PaystackPaymentReconciliationServiceTest {

    private static final String REFERENCE = "MH-booking-test";
    private static final long BOOKING_TOTAL_MINOR = 9_450_000L;

    @Mock private PaymentIntentRepository paymentIntentRepository;
    @Mock private PaystackClient paystackClient;
    @Mock private ListingUnlockEntitlementService entitlementService;
    @Mock private BookingRepository bookingRepository;
    @Mock private MockEscrowLedgerService escrowLedgerService;
    @Mock private MockPaymentAuditService auditService;
    @Mock private PaystackWebhookReceiptRepository receiptRepository;
    @Mock private org.springframework.beans.factory.ObjectProvider<BookingTestPayoutService> payouts;

    @Test void transferWebhookRequiresSignatureAndOnlyRequestsIndependentReconciliation() throws Exception {
        var worker=mock(BookingTestPayoutService.class);
        when(payouts.getIfAvailable()).thenReturn(worker);
        byte[] body="{\"event\":\"transfer.success\",\"data\":{\"reference\":\"mh-owner-00000000-0000-0000-0000-000000000001\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var service=service();
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->service.handleWebhook("00",body));
        verifyNoInteractions(worker,paymentIntentRepository,escrowLedgerService);
        var mac=javax.crypto.Mac.getInstance("HmacSHA512");
        mac.init(new javax.crypto.spec.SecretKeySpec("sk_test_sandbox".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA512"));
        String signature=java.util.HexFormat.of().formatHex(mac.doFinal(body));
        service.handleWebhook(signature,body);service.handleWebhook(signature,body);
        verify(worker,org.mockito.Mockito.times(2)).reconcileReference("mh-owner-00000000-0000-0000-0000-000000000001");
        verifyNoInteractions(paymentIntentRepository,escrowLedgerService,entitlementService);
    }

    @Test
    void successfulVerifiedBookingChargeMarksBookingPaidHeldAndPostsMockHold() {
        UUID renterId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent(
                renterId,
                PaymentType.BOOKING,
                null,
                bookingId,
                PaymentTargetKind.BOOKING,
                BOOKING_TOTAL_MINOR,
                PaymentIntent.KES,
                "booking-test-1"
        );
        intent.markPending(REFERENCE);
        Booking booking = mock(Booking.class);
        when(booking.getStatus()).thenReturn(BookingStatus.PAYMENT_PENDING);
        when(paymentIntentRepository.findByProviderReferenceForUpdate(REFERENCE))
                .thenReturn(Optional.of(intent));
        when(paystackClient.verify(REFERENCE)).thenReturn(new PaystackClient.VerificationResult(
                "success", REFERENCE, BOOKING_TOTAL_MINOR, PaymentIntent.KES
        ));
        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(booking));
        when(paymentIntentRepository.save(intent)).thenReturn(intent);
        when(bookingRepository.save(booking)).thenReturn(booking);

        PaymentIntent result = service().reconcile(REFERENCE);

        assertEquals(PaymentStatus.SUCCEEDED, result.getStatus());
        verify(booking).markPaidHeld();
        verify(bookingRepository).save(booking);
        verify(auditService).recordBookingPaidHeld(intent, booking);
        verify(escrowLedgerService).recordBookingHold(intent, booking);
    }

    @Test
    void pendingProviderChargeDoesNotFailBookingOrPostAHold() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent(
                UUID.randomUUID(), PaymentType.BOOKING, null, bookingId,
                PaymentTargetKind.BOOKING, 8_400_000L, PaymentIntent.KES,
                "booking-pending-1"
        );
        intent.markPending(REFERENCE);
        when(paymentIntentRepository.findByProviderReferenceForUpdate(REFERENCE))
                .thenReturn(Optional.of(intent));
        when(paystackClient.verify(REFERENCE)).thenReturn(new PaystackClient.VerificationResult(
                "pending", REFERENCE, 8_400_000L, PaymentIntent.KES
        ));

        PaymentIntent result = service().reconcile(REFERENCE);

        assertEquals(PaymentStatus.PENDING, result.getStatus());
        verify(paymentIntentRepository, never()).save(any());
        verifyNoInteractions(bookingRepository, escrowLedgerService, entitlementService);
    }

    private PaystackPaymentReconciliationService service() {
        return new PaystackPaymentReconciliationService(
                paymentIntentRepository,
                paystackClient,
                entitlementService,
                bookingRepository,
                escrowLedgerService,
                auditService,
                receiptRepository,
                new JsonMapper(),
                new PaystackProperties(true, "https://api.paystack.co", "sk_test_sandbox"),
                payouts
        );
    }
}
