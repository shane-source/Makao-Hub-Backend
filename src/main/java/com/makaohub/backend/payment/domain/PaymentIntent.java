package com.makaohub.backend.payment.domain;

import com.makaohub.backend.payment.service.ProviderPaymentStart;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-owned request to collect one payment.
 *
 * The amount and target identifiers are snapshots. They must never be
 * replaced with values supplied by a later client request.
 */
@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

    public static final String KES = "KES";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payer_user_id", nullable = false, updatable = false)
    private UUID payerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 40, updatable = false)
    private PaymentType paymentType;

    @Column(name = "listing_id", updatable = false)
    private UUID listingId;

    @Column(name = "booking_id", updatable = false)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, length = 24, updatable = false)
    private PaymentTargetKind targetKind;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentStatus status = PaymentStatus.CREATED;

    @Column(name = "provider_reference", length = 120, unique = true)
    private String providerReference;

    @Column(name = "authorization_url", length = 500)
    private String authorizationUrl;

    @Column(name = "provider_access_code", length = 160)
    private String providerAccessCode;

    @Column(name = "provider_display_text", length = 500)
    private String providerDisplayText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentIntent() {
    }

    public PaymentIntent(
            UUID payerUserId,
            PaymentType paymentType,
            UUID listingId,
            UUID bookingId,
            PaymentTargetKind targetKind,
            long amountMinor,
            String currency,
            String idempotencyKey
    ) {
        this.payerUserId = Objects.requireNonNull(
                payerUserId,
                "Payer user ID is required."
        );
        this.paymentType = Objects.requireNonNull(
                paymentType,
                "Payment type is required."
        );
        this.listingId = listingId;
        this.bookingId = bookingId;
        this.targetKind = Objects.requireNonNull(
                targetKind,
                "Payment target kind is required."
        );
        this.amountMinor = requirePositiveAmount(amountMinor);
        this.currency = normalizeCurrency(currency);
        this.idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        validateTarget();
    }

    public void markPending(String providerReference) {
        markPending(new ProviderPaymentStart(providerReference, null, null));
    }

    public void markPending(ProviderPaymentStart providerStart) {
        requireStatus(PaymentStatus.CREATED);
        Objects.requireNonNull(providerStart, "Provider start is required.");
        this.providerReference = normalizeProviderReference(providerStart.providerReference());
        this.authorizationUrl = normalizeOptional(providerStart.authorizationUrl(), 500);
        this.providerAccessCode = normalizeOptional(providerStart.accessCode(), 160);
        this.providerDisplayText = normalizeOptional(providerStart.displayText(), 500);
        this.status = PaymentStatus.PENDING;
    }

    public void markSucceeded(Instant completedAt) {
        requireStatus(PaymentStatus.PENDING);
        this.status = PaymentStatus.SUCCEEDED;
        this.completedAt = Objects.requireNonNull(
                completedAt,
                "Completion time is required."
        );
    }

    public void markFailed() {
        requireStatus(PaymentStatus.PENDING);
        this.status = PaymentStatus.FAILED;
    }

    public void markExpired() {
        requireStatus(PaymentStatus.PENDING);
        this.status = PaymentStatus.EXPIRED;
    }

    public void markRefundPending() {
        requireStatus(PaymentStatus.SUCCEEDED);
        this.status = PaymentStatus.REFUND_PENDING;
    }

    public void markRefunded(Instant completedAt) {
        requireStatus(PaymentStatus.REFUND_PENDING);
        this.status = PaymentStatus.REFUNDED;
        this.completedAt = Objects.requireNonNull(
                completedAt,
                "Refund completion time is required."
        );
    }

    public void markDisputed() {
        if (status != PaymentStatus.SUCCEEDED
                && status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Only active or successful payments can be disputed."
            );
        }
        this.status = PaymentStatus.DISPUTED;
    }

    private void requireStatus(PaymentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Invalid payment transition from " + status
                            + "; expected " + expected + "."
            );
        }
    }

    private void validateTarget() {
        boolean hasListing = listingId != null;
        boolean hasBooking = bookingId != null;

        if (paymentType == PaymentType.BOOKING
                && (!hasBooking || hasListing || targetKind != PaymentTargetKind.BOOKING)) {
            throw new IllegalArgumentException(
                    "Booking payments require only a booking ID."
            );
        }

        if ((paymentType == PaymentType.EXACT_LOCATION_UNLOCK
                || paymentType == PaymentType.VIDEO_UNLOCK
                || paymentType == PaymentType.PHONE_UNLOCK
                || paymentType == PaymentType.BOOST
                || paymentType == PaymentType.LISTING_ADD_ON)
                && (!hasListing
                || hasBooking
                || (targetKind != PaymentTargetKind.LANDLORD_LISTING
                && targetKind != PaymentTargetKind.SUBLESSOR_LISTING))) {
            throw new IllegalArgumentException(
                    "Listing payments require only a listing ID."
            );
        }

        if (paymentType == PaymentType.PLAN_UPGRADE
                && (hasListing || hasBooking || targetKind != PaymentTargetKind.NONE)) {
            throw new IllegalArgumentException(
                    "Plan upgrades cannot target a listing or booking."
            );
        }
    }

    private static long requirePositiveAmount(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "Payment amount must be positive."
            );
        }
        return amountMinor;
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null || !KES.equals(currency.strip().toUpperCase())) {
            throw new IllegalArgumentException(
                    "Only KES payments are supported."
            );
        }
        return KES;
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new IllegalArgumentException(
                    "A non-blank idempotency key of at most 100 characters is required."
            );
        }
        return key.strip();
    }

    private static String normalizeProviderReference(String reference) {
        if (reference == null || reference.isBlank() || reference.length() > 120) {
            throw new IllegalArgumentException(
                    "A provider reference is required."
            );
        }
        return reference.strip();
    }

    private static String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("Provider value is too long.");
        }
        return normalized;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPayerUserId() {
        return payerUserId;
    }

    public PaymentType getPaymentType() {
        return paymentType;
    }

    public UUID getListingId() {
        return listingId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public PaymentTargetKind getTargetKind() {
        return targetKind;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getAuthorizationUrl() {
        return authorizationUrl;
    }

    public String getProviderAccessCode() {
        return providerAccessCode;
    }

    public String getProviderDisplayText() {
        return providerDisplayText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getVersion() {
        return version;
    }
}
