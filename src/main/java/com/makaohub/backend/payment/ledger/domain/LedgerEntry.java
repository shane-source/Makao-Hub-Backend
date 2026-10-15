package com.makaohub.backend.payment.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable double-entry line for a recorded mock escrow hold. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    public static final String KES = "KES";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "payment_intent_id", nullable = false, updatable = false)
    private UUID paymentIntentId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 24, updatable = false)
    private LedgerAccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "component", nullable = false, length = 16, updatable = false)
    private LedgerComponent component;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8, updatable = false)
    private LedgerSide side;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(
            UUID transactionId,
            UUID paymentIntentId,
            UUID bookingId,
            LedgerAccountType accountType,
            LedgerComponent component,
            LedgerSide side,
            long amountMinor,
            String currency
    ) {
        this.transactionId = Objects.requireNonNull(transactionId);
        this.paymentIntentId = Objects.requireNonNull(paymentIntentId);
        this.bookingId = Objects.requireNonNull(bookingId);
        this.accountType = Objects.requireNonNull(accountType);
        this.component = Objects.requireNonNull(component);
        this.side = Objects.requireNonNull(side);
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Ledger amount must be positive.");
        }
        this.amountMinor = amountMinor;
        if (currency == null || !KES.equals(currency.strip().toUpperCase())) {
            throw new IllegalArgumentException("Only KES ledger entries are supported.");
        }
        this.currency = KES;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getBookingId() { return bookingId; }
    public LedgerAccountType getAccountType() { return accountType; }
    public LedgerComponent getComponent() { return component; }
    public LedgerSide getSide() { return side; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
