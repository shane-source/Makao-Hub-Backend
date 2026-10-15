package com.makaohub.backend.payment.api;

import com.makaohub.backend.payment.service.MockPaymentOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MockPaymentCompletionRequest(

        @NotBlank(message = "Provider reference is required.")
        String providerReference,

        @NotNull(message = "Mock payment outcome is required.")
        MockPaymentOutcome outcome
) {
}
