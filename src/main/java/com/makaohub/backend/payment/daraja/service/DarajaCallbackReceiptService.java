package com.makaohub.backend.payment.daraja.service;

import com.makaohub.backend.payment.daraja.api.DarajaStkCallback;
import com.makaohub.backend.payment.daraja.domain.DarajaCallbackReceipt;
import com.makaohub.backend.payment.daraja.repository.DarajaCallbackReceiptRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/** Stores callbacks idempotently without changing money state. */
@Service
@Profile("daraja")
public class DarajaCallbackReceiptService {

    private final DarajaCallbackReceiptRepository repository;

    public DarajaCallbackReceiptService(
            DarajaCallbackReceiptRepository repository
    ) {
        this.repository = repository;
    }

    @Transactional
    public DarajaCallbackReceipt record(DarajaStkCallback callback) {
        if (callback == null || callback.body() == null
                || callback.body().stkCallback() == null) {
            throw badRequest("The Daraja callback body is missing.");
        }

        DarajaStkCallback.StkCallback stk = callback.body().stkCallback();
        String checkoutRequestId = required(
                stk.checkoutRequestId(), "CheckoutRequestID"
        );
        String merchantRequestId = required(
                stk.merchantRequestId(), "MerchantRequestID"
        );
        if (stk.resultCode() == null) {
            throw badRequest("ResultCode is required.");
        }

        String description = clip(
                stk.resultDescription() == null
                        ? "No result description supplied."
                        : stk.resultDescription().strip(),
                1024
        );
        String receiptNumber = metadataText(stk.callbackMetadata(), "MpesaReceiptNumber");
        Long amount = metadataLong(stk.callbackMetadata(), "Amount");

        repository.insertIfAbsent(
                UUID.randomUUID(),
                checkoutRequestId,
                merchantRequestId,
                stk.resultCode(),
                description,
                receiptNumber,
                amount,
                Instant.now()
        );

        return repository.findByCheckoutRequestId(checkoutRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "The Daraja callback could not be stored."
                ));
    }

    private static String metadataText(
            DarajaStkCallback.CallbackMetadata metadata,
            String key
    ) {
        if (metadata == null || metadata.items() == null) {
            return null;
        }
        return metadata.items().stream()
                .filter(item -> key.equals(item.name()))
                .map(DarajaStkCallback.CallbackItem::value)
                .map(DarajaCallbackReceiptService::nodeText)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> clip(value, 120))
                .findFirst()
                .orElse(null);
    }

    private static Long metadataLong(
            DarajaStkCallback.CallbackMetadata metadata,
            String key
    ) {
        String value = metadataText(metadata, key);
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String nodeText(Object value) {
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return null;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw badRequest(name + " is required.");
        }
        return clip(value.strip(), 120);
    }

    private static String clip(String value, int maxLength) {
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
