package com.makaohub.backend.payment.paystack.api;

import com.makaohub.backend.payment.paystack.service.PaystackPaymentReconciliationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public Paystack webhook; authenticity is established by HMAC signature. */
@RestController
@Profile("paystack")
@RequestMapping("/api/v1/payments/paystack")
public class PaystackWebhookController {

    private final PaystackPaymentReconciliationService reconciliationService;

    public PaystackWebhookController(
            PaystackPaymentReconciliationService reconciliationService
    ) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping(
            value = "/webhook",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "x-paystack-signature", required = false)
            String signature,
            @RequestBody byte[] body
    ) {
        reconciliationService.handleWebhook(signature, body);
        return ResponseEntity.ok().build();
    }
}
