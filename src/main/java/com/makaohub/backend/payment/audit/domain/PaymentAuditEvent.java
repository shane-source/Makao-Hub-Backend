package com.makaohub.backend.payment.audit.domain;

import com.makaohub.backend.payment.domain.PaymentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only, non-PII record of a payment state transition.
 * Provider secrets, tokens and idempotency keys intentionally do not belong here.
 */
@Entity
@Table(name = "payment_audit_events")
@Immutable
public class PaymentAuditEvent {

    public static final String KES = "KES";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_intent_id", nullable = false, updatable = false)
    private UUID paymentIntentId;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "booking_id", updatable = false)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 40, updatable = false)
    private PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32, updatable = false)
    private PaymentAuditEventType eventType;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected PaymentAuditEvent() {
    }

    public PaymentAuditEvent(
            UUID paymentIntentId,
            UUID actorUserId,
            UUID bookingId,
            PaymentType paymentType,
            PaymentAuditEventType eventType,
            long amountMinor,
            String currency,
            Instant occurredAt
    ) {
        this.paymentIntentId = Objects.requireNonNull(paymentIntentId);
        this.actorUserId = Objects.requireNonNull(actorUserId);
        this.bookingId = bookingId;
        this.paymentType = Objects.requireNonNull(paymentType);
        this.eventType = Objects.requireNonNull(eventType);
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Audit amount must be positive.");
        }
        this.amountMinor = amountMinor;
        if (currency == null || !KES.equals(currency.strip().toUpperCase())) {
            throw new IllegalArgumentException("Only KES audit events are supported.");
        }
        this.currency = KES;
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }

    public UUID getId() { return id; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getActorUserId() { return actorUserId; }
    public UUID getBookingId() { return bookingId; }
    public PaymentType getPaymentType() { return paymentType; }
    public PaymentAuditEventType getEventType() { return eventType; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Instant getOccurredAt() { return occurredAt; }
}
