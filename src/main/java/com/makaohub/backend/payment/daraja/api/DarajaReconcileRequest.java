package com.makaohub.backend.payment.daraja.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DarajaReconcileRequest(
        @NotBlank
        @Size(max = 120)
        String checkoutRequestId
) {
}
