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
        Instant releaseEligibleAt,
        Instant releasedAt,
        String payoutReference,
        Instant createdAt,
        Instant updatedAt
) {
    public static BookingResponse from(Booking booking) {
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
                booking.getReleaseEligibleAt(),
                booking.getReleasedAt(),
                booking.getPayoutReference(),
                booking.getCreatedAt(),
                booking.getUpdatedAt()
        );
    }
}
