package com.makaohub.backend.payment.daraja.service;

import com.makaohub.backend.payment.audit.domain.PaymentAuditEventType;
import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.payment.daraja.client.DarajaStkQueryClient;
import com.makaohub.backend.payment.daraja.domain.DarajaCallbackReceipt;
import com.makaohub.backend.payment.daraja.repository.DarajaCallbackReceiptRepository;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.entitlement.service.ListingUnlockEntitlementService;
import com.makaohub.backend.payment.ledger.service.MockEscrowLedgerService;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingStatus;
import com.makaohub.backend.renter.repository.BookingRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Settles a pending intent only after Daraja's transaction query confirms it.
 * Callback data is used for correlation and amount mismatch detection, never
 * as the authority for a successful payment.
 */
@Service
@Profile("daraja")
public class DarajaPaymentReconciliationService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final DarajaStkQueryClient queryClient;
    private final DarajaCallbackReceiptRepository callbackRepository;
    private final MockPaymentAuditService auditService;
    private final ListingUnlockEntitlementService entitlementService;
    private final BookingRepository bookingRepository;
    private final MockEscrowLedgerService escrowLedgerService;

    public DarajaPaymentReconciliationService(
            PaymentIntentRepository paymentIntentRepository,
            DarajaStkQueryClient queryClient,
            DarajaCallbackReceiptRepository callbackRepository,
            MockPaymentAuditService auditService,
            ListingUnlockEntitlementService entitlementService,
            BookingRepository bookingRepository,
            MockEscrowLedgerService escrowLedgerService
    ) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.queryClient = queryClient;
        this.callbackRepository = callbackRepository;
        this.auditService = auditService;
        this.entitlementService = entitlementService;
        this.bookingRepository = bookingRepository;
        this.escrowLedgerService = escrowLedgerService;
    }

    @Transactional
    public PaymentIntent reconcile(String checkoutRequestId) {
        String providerReference = normalize(checkoutRequestId);
        DarajaStkQueryClient.StkQueryResponse query =
                queryClient.query(providerReference);
        return applyQueryResult(providerReference, query);
    }

    protected PaymentIntent applyQueryResult(
            String providerReference,
            DarajaStkQueryClient.StkQueryResponse query
    ) {
        PaymentIntent intent = paymentIntentRepository
                .findByProviderReferenceForUpdate(providerReference)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Payment intent was not found."
                ));

        if (intent.getStatus() != PaymentStatus.PENDING) {
            return intent;
        }

        if (!verifyCallbackAmount(intent, providerReference)) {
            return intent;
        }

        if (query.resultCode() == 0) {
            intent.markSucceeded(Instant.now());
            intent = paymentIntentRepository.save(intent);
            auditService.recordProviderResult(
                    intent, PaymentAuditEventType.PROVIDER_SUCCEEDED
            );
            entitlementService.activateForSuccessfulPayment(intent);
            settleBookingIfNeeded(intent);
            return intent;
        }

        intent.markFailed();
        intent = paymentIntentRepository.save(intent);
        auditService.recordProviderResult(
                intent, PaymentAuditEventType.PROVIDER_FAILED
        );
        resetBookingIfNeeded(intent);
        return intent;
    }

    private boolean verifyCallbackAmount(
            PaymentIntent intent,
            String providerReference
    ) {
        DarajaCallbackReceipt receipt = callbackRepository
                .findByCheckoutRequestId(providerReference)
                .orElse(null);
        if (receipt != null && receipt.getAmountMinor() != null
                && receipt.getAmountMinor() != intent.getAmountMinor()) {
            intent.markDisputed();
            paymentIntentRepository.save(intent);
            return false;
        }
        return true;
    }

    private void settleBookingIfNeeded(PaymentIntent intent) {
        if (intent.getBookingId() == null) {
            return;
        }
        Booking booking = bookingRepository.findById(intent.getBookingId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The booking for this payment no longer exists."
                ));
        if (booking.getStatus() == BookingStatus.PAYMENT_PENDING) {
            booking.markPaidHeld();
            bookingRepository.save(booking);
            auditService.recordBookingPaidHeld(intent, booking);
        }
        escrowLedgerService.recordBookingHold(intent, booking);
    }

    private void resetBookingIfNeeded(PaymentIntent intent) {
        if (intent.getBookingId() == null) {
            return;
        }
        Booking booking = bookingRepository.findById(intent.getBookingId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The booking for this payment no longer exists."
                ));
        if (booking.getStatus() == BookingStatus.PAYMENT_PENDING) {
            booking.paymentFailed();
            bookingRepository.save(booking);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A valid checkout request ID is required."
            );
        }
        return value.strip();
    }
}
