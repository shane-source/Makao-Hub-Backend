package com.makaohub.backend.payment.api;

import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.domain.PaymentType;

import java.time.Instant;
import java.util.UUID;

public record PaymentIntentResponse(
        UUID id,
        PaymentType paymentType,
        PaymentTargetKind targetKind,
        UUID listingId,
        UUID bookingId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String providerReference,
        String authorizationUrl,
        String providerAccessCode,
        String providerDisplayText,
        Instant createdAt,
        Instant completedAt
) {
    public static PaymentIntentResponse from(PaymentIntent intent) {
        return new PaymentIntentResponse(
                intent.getId(),
                intent.getPaymentType(),
                intent.getTargetKind(),
                intent.getListingId(),
                intent.getBookingId(),
                intent.getAmountMinor(),
                intent.getCurrency(),
                intent.getStatus(),
                intent.getProviderReference(),
                intent.getAuthorizationUrl(),
                intent.getProviderAccessCode(),
                intent.getProviderDisplayText(),
                intent.getCreatedAt(),
                intent.getCompletedAt()
        );
    }
}
