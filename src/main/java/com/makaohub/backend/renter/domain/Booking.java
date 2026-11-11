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

    @Column(name = "owner_move_in_confirmed_at")
    private Instant ownerMoveInConfirmedAt;

    @Column(name = "move_in_accepted") private Boolean moveInAccepted;
    @Column(name = "move_in_answered_at") private Instant moveInAnsweredAt;
    @Column(name = "move_in_response_key", length = 100) private String moveInResponseKey;
    @Column(name = "test_payout_status", nullable = false, length = 32) private String testPayoutStatus = "NOT_QUEUED";

    @Column(name = "release_eligible_at")
    private Instant releaseEligibleAt;

    @Column(name = "owner_release_requested_at")
    private Instant ownerReleaseRequestedAt;

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
        if (!bothPartiesAgreed() || moveInConfirmedAt == null || ownerMoveInConfirmedAt == null) {
            throw new IllegalStateException(
                    "Both renter and owner must confirm move-in before activation."
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

    /** Records renter move-in; release must still wait for the owner's confirmation. */
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
        }
    }

    /** The second confirmation starts the five-minute TEST release wait; retries preserve its deadline. */
    public boolean confirmOwnerMoveIn(Instant confirmedAt) {
        requirePaidHeld();
        if (!bothPartiesAgreed() || moveInConfirmedAt == null) {
            throw new IllegalStateException("The renter must confirm move-in before the owner can confirm it.");
        }
        if (ownerMoveInConfirmedAt != null) return false;
        Instant confirmed = Objects.requireNonNull(confirmedAt, "Confirmation time is required.");
        if (confirmed.isBefore(moveInConfirmedAt)) throw new IllegalArgumentException("Owner confirmation predates renter move-in.");
        ownerMoveInConfirmedAt = confirmed;
        releaseEligibleAt = confirmed.plus(5, ChronoUnit.MINUTES);
        return true;
    }

    /** Records one owner request for staff review, without changing release eligibility. */
    public boolean requestReleaseReview(Instant requestedAt) {
        requirePaidHeld();
        if (!bothPartiesAgreed() || moveInConfirmedAt == null || ownerMoveInConfirmedAt == null) {
            throw new IllegalStateException("Both renter and owner must confirm move-in before payout review.");
        }
        if (ownerReleaseRequestedAt != null) return false;
        Instant requested = Objects.requireNonNull(requestedAt, "Request time is required.");
        if (requested.isBefore(moveInConfirmedAt)) throw new IllegalArgumentException("Request predates move-in.");
        ownerReleaseRequestedAt = requested;
        return true;
    }

    public void markReleased(
            Instant releasedAt,
            String payoutReference,
            String releaseIdempotencyKey
    ) {
        requirePaidHeld();
        if (!Boolean.TRUE.equals(moveInAccepted)) throw new IllegalStateException("A fresh renter True response is required.");
        if (moveInConfirmedAt == null || ownerMoveInConfirmedAt == null || releaseEligibleAt == null) {
            throw new IllegalStateException(
                    "The owner report and renter True response are required before release."
            );
        }
        Instant releaseTime = Objects.requireNonNull(releasedAt, "Release time is required.");
        if (releaseTime.isBefore(releaseEligibleAt)) {
            throw new IllegalStateException(
                    "The five-minute wait after the renter's True response has not elapsed."
            );
        }
        this.releasedAt = releaseTime;
        this.payoutReference = normalizeReference(payoutReference, "Payout reference");
        this.releaseIdempotencyKey = normalizeIdempotencyKey(releaseIdempotencyKey);
        status = BookingStatus.ACTIVE;
    }

    public boolean bothPartiesAgreed() {
        return renterAgreedAt != null && ownerAgreedAt != null;
    }

    public boolean isReleaseEligible(Instant now) {
        return Boolean.TRUE.equals(moveInAccepted) && ownerMoveInConfirmedAt != null && releaseEligibleAt != null
                && now != null
                && !now.isBefore(releaseEligibleAt);
    }

    /** Owner reports first; old timestamps never stand in for the renter's fresh answer. */
    public boolean reportOwnerMoveIn(Instant now) {
        requirePaidHeld();
        if (ownerMoveInConfirmedAt != null) return false;
        ownerMoveInConfirmedAt = Objects.requireNonNull(now);
        releaseEligibleAt = null;
        return true;
    }

    public boolean respondToOwnerMoveIn(boolean accepted, Instant now, String key) {
        requirePaidHeld();
        if (ownerMoveInConfirmedAt == null)
            throw new IllegalStateException("Wait for the owner to report move-in first.");
        if (moveInAccepted != null) {
            if (moveInAccepted == accepted) return false;
            throw new IllegalStateException("Your response is already recorded. Contact staff for review.");
        }
        Instant answer = Objects.requireNonNull(now);
        if (answer.isBefore(ownerMoveInConfirmedAt)) throw new IllegalArgumentException("Response predates owner report.");
        String responseKey = normalizeIdempotencyKey(key);
        moveInAccepted = accepted; moveInAnsweredAt = answer; moveInResponseKey = responseKey;
        if (accepted) { moveInConfirmedAt = answer; releaseEligibleAt = answer.plus(5, ChronoUnit.MINUTES); }
        else releaseEligibleAt = null;
        testPayoutStatus = accepted ? "WAITING" : "REVIEW_REQUIRED";
        return true;
    }

    public void setTestPayoutStatus(String value) { testPayoutStatus = Objects.requireNonNull(value); }

    private void requirePaidHeld() {
        if (status != BookingStatus.PAID_HELD) {
            throw new IllegalStateException(
                    "The booking payment must be confirmed and held first."
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
    public Instant getOwnerMoveInConfirmedAt() { return ownerMoveInConfirmedAt; }
    public Boolean getMoveInAccepted() { return moveInAccepted; }
    public Instant getMoveInAnsweredAt() { return moveInAnsweredAt; }
    public String getTestPayoutStatus() { return testPayoutStatus; }
    public Instant getReleaseEligibleAt() { return releaseEligibleAt; }
    public Instant getOwnerReleaseRequestedAt() { return ownerReleaseRequestedAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public String getPayoutReference() { return payoutReference; }
    public String getReleaseIdempotencyKey() { return releaseIdempotencyKey; }
}
