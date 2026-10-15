package com.makaohub.backend.payment.entitlement.domain;

import com.makaohub.backend.payment.domain.PaymentType;
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

/** Server-created permission granted by a successful listing unlock payment. */
@Entity
@Table(name = "listing_unlock_entitlements")
public class ListingUnlockEntitlement {

    public static final String KES = "KES";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_intent_id", nullable = false, unique = true, updatable = false)
    private UUID paymentIntentId;

    @Column(name = "payer_user_id", nullable = false, updatable = false)
    private UUID payerUserId;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "unlock_type", nullable = false, length = 40, updatable = false)
    private PaymentType unlockType;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ListingUnlockStatus status = ListingUnlockStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ListingUnlockEntitlement() {
    }

    public ListingUnlockEntitlement(UUID paymentIntentId, UUID payerUserId,
                                    UUID listingId, PaymentType unlockType,
                                    long amountMinor, String currency) {
        this.paymentIntentId = Objects.requireNonNull(paymentIntentId);
        this.payerUserId = Objects.requireNonNull(payerUserId);
        this.listingId = Objects.requireNonNull(listingId);
        if (unlockType != PaymentType.EXACT_LOCATION_UNLOCK
                && unlockType != PaymentType.VIDEO_UNLOCK
                && unlockType != PaymentType.PHONE_UNLOCK) {
            throw new IllegalArgumentException("Payment type is not a listing unlock.");
        }
        this.unlockType = unlockType;
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Unlock amount must be positive.");
        }
        this.amountMinor = amountMinor;
        if (currency == null || !KES.equals(currency.strip().toUpperCase())) {
            throw new IllegalArgumentException("Only KES unlocks are supported.");
        }
        this.currency = KES;
    }

    public void revoke(Instant revokedAt) {
        if (status != ListingUnlockStatus.ACTIVE) {
            throw new IllegalStateException("Only active entitlements can be revoked.");
        }
        this.status = ListingUnlockStatus.REVOKED;
        this.revokedAt = Objects.requireNonNull(revokedAt);
    }

    public void refund() {
        if (status != ListingUnlockStatus.ACTIVE) {
            throw new IllegalStateException("Only active entitlements can be refunded.");
        }
        this.status = ListingUnlockStatus.REFUNDED;
    }

    public UUID getId() { return id; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getPayerUserId() { return payerUserId; }
    public UUID getListingId() { return listingId; }
    public PaymentType getUnlockType() { return unlockType; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public ListingUnlockStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
