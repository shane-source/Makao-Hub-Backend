package com.makaohub.backend.renter.api;

import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingListingKind;
import com.makaohub.backend.renter.domain.BookingStatus;

import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID listingId,
        BookingListingKind listingKind,
        UUID sourceApplicationId,
        long rentAmountMinor,
        long depositAmountMinor,
        long commissionAmountMinor,
        long totalAmountMinor,
        String currency,
        BookingStatus status,
        Instant renterAgreedAt,
        Instant ownerAgreedAt,
        Instant moveInConfirmedAt,
        Instant ownerMoveInConfirmedAt,
        Instant releaseEligibleAt,
        Instant ownerReleaseRequestedAt,
        Instant releasedAt,
        String payoutReference,
        Instant createdAt,
        Instant updatedAt,
        String renterName,
        String ownerName,
        Boolean moveInAccepted,
        Instant moveInAnsweredAt,
        String testPayoutStatus
) {
    public static BookingResponse from(Booking booking) {
        return from(booking, null, null);
    }

    /** Names are supplied only for the owner-scoped booking endpoint. */
    public static BookingResponse from(Booking booking, String renterName, String ownerName) {
        return new BookingResponse(
                booking.getId(),
                booking.getListingId(),
                booking.getListingKind(),
                booking.getSourceApplicationId(),
                booking.getRentAmountMinor(),
                booking.getDepositAmountMinor(),
                booking.getCommissionAmountMinor(),
                booking.getTotalAmountMinor(),
                booking.getCurrency(),
                booking.getStatus(),
                booking.getRenterAgreedAt(),
                booking.getOwnerAgreedAt(),
                booking.getMoveInConfirmedAt(),
                booking.getOwnerMoveInConfirmedAt(),
                booking.getReleaseEligibleAt(),
                booking.getOwnerReleaseRequestedAt(),
                booking.getReleasedAt(),
                booking.getPayoutReference(),
                booking.getCreatedAt(),
                booking.getUpdatedAt(),
                renterName,
                ownerName,
                booking.getMoveInAccepted(), booking.getMoveInAnsweredAt(), booking.getTestPayoutStatus()
        );
    }
}
