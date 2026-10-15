package com.makaohub.backend.payment.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Identifies a payment without placing payment data in a URL. */
public record PaymentStatusRequest(
        @NotNull(message = "Payment intent ID is required.")
        UUID paymentIntentId
) {
}
