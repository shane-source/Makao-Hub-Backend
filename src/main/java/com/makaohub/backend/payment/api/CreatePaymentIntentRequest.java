package com.makaohub.backend.payment.api;

import com.makaohub.backend.payment.domain.PaymentType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreatePaymentIntentRequest(

        @NotNull(message = "Payment type is required.")
        PaymentType paymentType,

        UUID listingId,

        UUID bookingId
) {
}
