package com.makaohub.backend.renter.domain;

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
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** A booking from an approved application with immutable KES amount snapshots. */
@Entity
@Table(name = "bookings")
public class Booking {

    public static final String KES = "KES";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "renter_id", nullable = false, updatable = false)
    private UUID renterId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "listing_kind", nullable = false, length = 24, updatable = false)
    private BookingListingKind listingKind;

    @Column(name = "source_application_id", nullable = false, updatable = false)
    private UUID sourceApplicationId;

    @Column(name = "rent_amount_minor", nullable = false, updatable = false)
    private long rentAmountMinor;

    @Column(name = "deposit_amount_minor", nullable = false, updatable = false)
    private long depositAmountMinor;

    @Column(name = "commission_amount_minor", nullable = false, updatable = false)
    private long commissionAmountMinor;

    @Column(name = "total_amount_minor", nullable = false, updatable = false)
    private long totalAmountMinor;

    @Column(name = "renter_agreed_at")
    private Instant renterAgreedAt;

    @Column(name = "owner_agreed_at")
    private Instant ownerAgreedAt;

    @Column(name = "move_in_confirmed_at")
    private Instant moveInConfirmedAt;

    @Column(name = "release_eligible_at")
    private Instant releaseEligibleAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "payout_reference", length = 120)
    private String payoutReference;

    @Column(name = "release_idempotency_key", length = 100)
    private String releaseIdempotencyKey;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private BookingStatus status = BookingStatus.AWAITING_PAYMENT;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Booking() {
    }

    public Booking(
            UUID renterId,
            UUID ownerId,
            UUID listingId,
            BookingListingKind listingKind,
            UUID sourceApplicationId,
            long rentAmountMinor,
            long depositAmountMinor,
            long commissionAmountMinor,
            String currency
    ) {
        this.renterId = Objects.requireNonNull(renterId, "Renter ID is required.");
        this.ownerId = Objects.requireNonNull(ownerId, "Owner ID is required.");
        if (renterId.equals(ownerId)) {
            throw new IllegalArgumentException("Renter and owner must be different accounts.");
        }
        this.listingId = Objects.requireNonNull(listingId, "Listing ID is required.");
        this.listingKind = Objects.requireNonNull(listingKind, "Listing kind is required.");
        this.sourceApplicationId = Objects.requireNonNull(
                sourceApplicationId,
                "Source application ID is required."
        );
        this.rentAmountMinor = positive(rentAmountMinor, "Rent amount");
        this.depositAmountMinor = positive(depositAmountMinor, "Deposit amount");
        if (commissionAmountMinor < 0) {
            throw new IllegalArgumentException("Commission amount cannot be negative.");
        }
        this.commissionAmountMinor = commissionAmountMinor;
        this.totalAmountMinor = Math.addExact(
                Math.addExact(this.rentAmountMinor, this.depositAmountMinor),
                this.commissionAmountMinor
        );
        this.currency = normalizeCurrency(currency);
    }

    /** Compatibility constructor for legacy bookings with no commission snapshot. */
    public Booking(
            UUID renterId,
            UUID ownerId,
            UUID listingId,
            BookingListingKind listingKind,
            UUID sourceApplicationId,
            long rentAmountMinor,
            long depositAmountMinor,
            String currency
    ) {
        this(
                renterId,
                ownerId,
                listingId,
                listingKind,
                sourceApplicationId,
                rentAmountMinor,
                depositAmountMinor,
                0,
                currency
        );
    }

    public void beginPayment() {
        requireStatus(BookingStatus.AWAITING_PAYMENT);
        status = BookingStatus.PAYMENT_PENDING;
    }

    public void markPaidHeld() {
        requireStatus(BookingStatus.PAYMENT_PENDING);
        status = BookingStatus.PAID_HELD;
    }

    public void paymentFailed() {
        requireStatus(BookingStatus.PAYMENT_PENDING);
        status = BookingStatus.AWAITING_PAYMENT;
    }

    public void activate() {
        requireStatus(BookingStatus.PAID_HELD);
        if (!bothPartiesAgreed()) {
            throw new IllegalStateException(
                    "Both renter and owner must confirm before activation."
            );
        }
        status = BookingStatus.ACTIVE;
    }

    public void confirmRenterAgreement(Instant confirmedAt) {
        requirePaidHeld();
        if (renterAgreedAt == null) {
            renterAgreedAt = Objects.requireNonNull(confirmedAt, "Confirmation time is required.");
        }
    }

    public void confirmOwnerAgreement(Instant confirmedAt) {
        requirePaidHeld();
        if (ownerAgreedAt == null) {
            ownerAgreedAt = Objects.requireNonNull(confirmedAt, "Confirmation time is required.");
        }
    }

    /**
     * Records the renter's server-timestamped move-in confirmation and starts
     * the mandatory 24-hour release waiting period.
     */
    public void confirmMoveIn(Instant confirmedAt) {
        requirePaidHeld();
        if (!bothPartiesAgreed()) {
            throw new IllegalStateException(
                    "Both renter and owner must confirm before move-in."
            );
        }
        if (moveInConfirmedAt == null) {
            Instant confirmed = Objects.requireNonNull(
                    confirmedAt, "Move-in confirmation time is required."
            );
            moveInConfirmedAt = confirmed;
            releaseEligibleAt = confirmed.plus(24, ChronoUnit.HOURS);
        }
    }

    public void markReleased(
            Instant releasedAt,
            String payoutReference,
            String releaseIdempotencyKey
    ) {
        requirePaidHeld();
        if (!bothPartiesAgreed()) {
            throw new IllegalStateException(
                "Both renter and owner must confirm before release."
            );
        }
        if (moveInConfirmedAt == null || releaseEligibleAt == null) {
            throw new IllegalStateException(
                    "The renter must confirm move-in before release."
            );
        }
        this.releasedAt = Objects.requireNonNull(releasedAt, "Release time is required.");
        if (this.releasedAt.isBefore(releaseEligibleAt)) {
            throw new IllegalStateException(
                    "The 24-hour move-in holding period has not elapsed."
            );
        }
        this.payoutReference = normalizeReference(payoutReference, "Payout reference");
        this.releaseIdempotencyKey = normalizeIdempotencyKey(releaseIdempotencyKey);
        status = BookingStatus.ACTIVE;
    }

    public boolean bothPartiesAgreed() {
        return renterAgreedAt != null && ownerAgreedAt != null;
    }

    public boolean isReleaseEligible(Instant now) {
        return releaseEligibleAt != null
                && now != null
                && !now.isBefore(releaseEligibleAt);
    }

    private void requirePaidHeld() {
        if (status != BookingStatus.PAID_HELD) {
            throw new IllegalStateException(
                    "The booking must be paid and held before agreement confirmation."
            );
        }
    }

    public void cancel() {
        if (status != BookingStatus.AWAITING_PAYMENT) {
            throw new IllegalStateException("Only unpaid bookings can be cancelled.");
        }
        status = BookingStatus.CANCELLED;
    }

    private void requireStatus(BookingStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Invalid booking transition from " + status
                            + "; expected " + expected + "."
            );
        }
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null || !KES.equals(currency.strip().toUpperCase())) {
            throw new IllegalArgumentException("Only KES bookings are supported.");
        }
        return KES;
    }

    private static String normalizeReference(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return value.strip();
    }

    private static String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException(
                    "Release idempotency key must contain 1 to 100 characters."
            );
        }
        return value.strip();
    }

    public UUID getId() { return id; }
    public UUID getRenterId() { return renterId; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getListingId() { return listingId; }
    public BookingListingKind getListingKind() { return listingKind; }
    public UUID getSourceApplicationId() { return sourceApplicationId; }
    public long getRentAmountMinor() { return rentAmountMinor; }
    public long getDepositAmountMinor() { return depositAmountMinor; }
    public long getCommissionAmountMinor() { return commissionAmountMinor; }
    public long getTotalAmountMinor() { return totalAmountMinor; }
    public String getCurrency() { return currency; }
    public BookingStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public Instant getRenterAgreedAt() { return renterAgreedAt; }
    public Instant getOwnerAgreedAt() { return ownerAgreedAt; }
    public Instant getMoveInConfirmedAt() { return moveInConfirmedAt; }
    public Instant getReleaseEligibleAt() { return releaseEligibleAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public String getPayoutReference() { return payoutReference; }
    public String getReleaseIdempotencyKey() { return releaseIdempotencyKey; }
}
