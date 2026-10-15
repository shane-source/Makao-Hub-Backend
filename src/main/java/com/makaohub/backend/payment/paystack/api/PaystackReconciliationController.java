package com.makaohub.backend.payment.paystack.api;

import com.makaohub.backend.payment.api.PaymentIntentResponse;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.payment.paystack.service.PaystackPaymentReconciliationService;
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

/** Lets the payment owner request an authoritative Paystack verification. */
@RestController
@Profile("paystack")
@RequestMapping("/api/v1/payments/paystack")
public class PaystackReconciliationController {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaystackPaymentReconciliationService reconciliationService;

    public PaystackReconciliationController(
            PaymentIntentRepository paymentIntentRepository,
            PaystackPaymentReconciliationService reconciliationService
    ) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/reconcile")
    public PaymentIntentResponse reconcile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PaystackReconcileRequest request
    ) {
        UUID userId = userId(jwt);
        String reference = request.reference().strip();
        paymentIntentRepository.findByProviderReferenceAndPayerUserId(reference, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Payment intent was not found."
                ));
        return PaymentIntentResponse.from(reconciliationService.reconcile(reference));
    }

    private static UUID userId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "A valid access token is required."
            );
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "A valid access token is required."
            );
        }
    }
}
