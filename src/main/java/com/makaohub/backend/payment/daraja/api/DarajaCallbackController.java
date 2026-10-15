package com.makaohub.backend.payment.daraja.api;

import com.makaohub.backend.payment.daraja.service.DarajaCallbackReceiptService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public Daraja callback endpoint; authorization is by payment correlation. */
@RestController
@Profile("daraja")
@RequestMapping("/api/v1/payments/daraja")
public class DarajaCallbackController {

    private final DarajaCallbackReceiptService receiptService;

    public DarajaCallbackController(
            DarajaCallbackReceiptService receiptService
    ) {
        this.receiptService = receiptService;
    }

    @PostMapping(
            value = "/callback",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public CallbackAcknowledgement receive(
            @RequestBody DarajaStkCallback callback
    ) {
        receiptService.record(callback);
        return new CallbackAcknowledgement(0, "Accepted");
    }

    public record CallbackAcknowledgement(
            int resultCode,
            String resultDescription
    ) {
    }
}
