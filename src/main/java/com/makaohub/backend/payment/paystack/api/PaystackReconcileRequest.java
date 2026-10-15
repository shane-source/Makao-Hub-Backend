package com.makaohub.backend.payment.paystack.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaystackReconcileRequest(
        @NotBlank @Size(max = 120) String reference
) {
}
