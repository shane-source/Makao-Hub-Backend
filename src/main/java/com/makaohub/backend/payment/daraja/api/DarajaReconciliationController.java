package com.makaohub.backend.payment.daraja.api;

import com.makaohub.backend.payment.api.PaymentIntentResponse;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.payment.daraja.service.DarajaPaymentReconciliationService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Lets only the payment owner request a status reconciliation. */
@RestController
@Profile("daraja")
@RequestMapping("/api/v1/payments/daraja")
public class DarajaReconciliationController {

    private final PaymentIntentRepository paymentIntentRepository;
    private final DarajaPaymentReconciliationService reconciliationService;

    public DarajaReconciliationController(
            PaymentIntentRepository paymentIntentRepository,
            DarajaPaymentReconciliationService reconciliationService
    ) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/reconcile")
    public PaymentIntentResponse reconcile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DarajaReconcileRequest request
    ) {
        UUID userId = userId(jwt);
        String checkoutRequestId = request.checkoutRequestId().strip();

        paymentIntentRepository
                .findByProviderReferenceAndPayerUserId(checkoutRequestId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Payment intent was not found."
                ));

        return PaymentIntentResponse.from(
                reconciliationService.reconcile(checkoutRequestId)
        );
    }

    private static UUID userId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "A valid access token is required."
            );
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "A valid access token is required."
            );
        }
    }
}
