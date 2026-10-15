package com.makaohub.backend.payment.audit.service;

import com.makaohub.backend.payment.audit.domain.PaymentAuditEvent;
import com.makaohub.backend.payment.audit.domain.PaymentAuditEventType;
import com.makaohub.backend.payment.audit.repository.PaymentAuditEventRepository;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.renter.domain.Booking;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Writes de-duplicated, append-only mock-payment audit events. */
@Service
public class MockPaymentAuditService {

    private final PaymentAuditEventRepository repository;

    public MockPaymentAuditService(PaymentAuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void recordIntentCreated(PaymentIntent intent) {
        record(intent, null, PaymentAuditEventType.INTENT_CREATED);
    }

    @Transactional
    public void recordProviderResult(
            PaymentIntent intent,
            PaymentAuditEventType eventType
    ) {
        if (eventType != PaymentAuditEventType.PROVIDER_SUCCEEDED
                && eventType != PaymentAuditEventType.PROVIDER_FAILED) {
            throw new IllegalArgumentException("Event is not a provider result.");
        }
        record(intent, null, eventType);
    }

    @Transactional
    public void recordBookingPaidHeld(PaymentIntent intent, Booking booking) {
        record(intent, booking, PaymentAuditEventType.BOOKING_PAID_HELD);
    }

    @Transactional
    public void recordLedgerPosted(PaymentIntent intent, Booking booking) {
        record(intent, booking, PaymentAuditEventType.LEDGER_POSTED);
    }

    @Transactional
    public void recordRenterAgreementConfirmed(PaymentIntent intent, Booking booking) {
        record(intent, booking, PaymentAuditEventType.RENTER_AGREEMENT_CONFIRMED);
    }

    @Transactional
    public void recordOwnerAgreementConfirmed(PaymentIntent intent, Booking booking) {
        record(intent, booking, PaymentAuditEventType.OWNER_AGREEMENT_CONFIRMED);
    }

    @Transactional
    public void recordMoveInConfirmed(PaymentIntent intent, Booking booking) {
        record(intent, booking, PaymentAuditEventType.MOVE_IN_CONFIRMED);
    }

    @Transactional
    public void recordPaymentDisputed(PaymentIntent intent) {
        record(intent, null, PaymentAuditEventType.PAYMENT_DISPUTED);
    }

    @Transactional
    public void recordEscrowReleased(
            PaymentIntent intent,
            Booking booking,
            UUID actorUserId
    ) {
        record(intent, booking, PaymentAuditEventType.ESCROW_RELEASED, actorUserId);
    }

    private void record(
            PaymentIntent intent,
            Booking booking,
            PaymentAuditEventType eventType
    ) {
        record(intent, booking, eventType, intent.getPayerUserId());
    }

    private void record(
            PaymentIntent intent,
            Booking booking,
            PaymentAuditEventType eventType,
            UUID actorUserId
    ) {
        if (repository.existsByPaymentIntentIdAndEventType(intent.getId(), eventType)) {
            return;
        }
        repository.save(new PaymentAuditEvent(
                intent.getId(),
                actorUserId,
                booking == null ? intent.getBookingId() : booking.getId(),
                intent.getPaymentType(),
                eventType,
                intent.getAmountMinor(),
                intent.getCurrency(),
                Instant.now()
        ));
    }
}
