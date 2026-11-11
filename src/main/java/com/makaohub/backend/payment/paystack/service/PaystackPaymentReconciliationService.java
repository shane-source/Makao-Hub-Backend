package com.makaohub.backend.payment.paystack.service;

import com.makaohub.backend.payment.audit.domain.PaymentAuditEventType;
import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.entitlement.service.ListingUnlockEntitlementService;
import com.makaohub.backend.payment.ledger.service.MockEscrowLedgerService;
import com.makaohub.backend.payment.paystack.client.PaystackClient;
import com.makaohub.backend.payment.paystack.domain.PaystackWebhookReceipt;
import com.makaohub.backend.payment.paystack.repository.PaystackWebhookReceiptRepository;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.BookingStatus;
import com.makaohub.backend.renter.repository.BookingRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Verifies Paystack events and only then settles local payment state. */
@Service
@Profile("paystack")
public class PaystackPaymentReconciliationService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaystackClient paystackClient;
    private final ListingUnlockEntitlementService entitlementService;
    private final BookingRepository bookingRepository;
    private final MockEscrowLedgerService escrowLedgerService;
    private final MockPaymentAuditService auditService;
    private final PaystackWebhookReceiptRepository receiptRepository;
    private final JsonMapper jsonMapper;
    private final String webhookSecret;
    private final org.springframework.beans.factory.ObjectProvider<BookingTestPayoutService> payouts;

    public PaystackPaymentReconciliationService(
            PaymentIntentRepository paymentIntentRepository,
            PaystackClient paystackClient,
            ListingUnlockEntitlementService entitlementService,
            BookingRepository bookingRepository,
            MockEscrowLedgerService escrowLedgerService,
            MockPaymentAuditService auditService,
            PaystackWebhookReceiptRepository receiptRepository,
            JsonMapper jsonMapper,
            com.makaohub.backend.payment.paystack.config.PaystackProperties properties,
            org.springframework.beans.factory.ObjectProvider<BookingTestPayoutService> payouts
    ) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paystackClient = paystackClient;
        this.entitlementService = entitlementService;
        this.bookingRepository = bookingRepository;
        this.escrowLedgerService = escrowLedgerService;
        this.auditService = auditService;
        this.receiptRepository = receiptRepository;
        this.jsonMapper = jsonMapper;
        this.webhookSecret = properties.secretKey();
        this.payouts = payouts;
    }

    @Transactional
    public PaymentIntent reconcile(String reference) {
        String normalized = normalizeReference(reference);
        PaystackClient.VerificationResult result = paystackClient.verify(normalized);
        PaymentIntent intent = paymentIntentRepository
                .findByProviderReferenceForUpdate(normalized)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Payment intent was not found."
                ));

        if (intent.getStatus() != PaymentStatus.PENDING) {
            return intent;
        }

        if (!normalized.equals(result.reference())
                || result.amountMinor() != intent.getAmountMinor()
                || !PaymentIntent.KES.equalsIgnoreCase(result.currency())) {
            intent.markDisputed();
            intent = paymentIntentRepository.save(intent);
            auditService.recordPaymentDisputed(intent);
            return intent;
        }

        String providerStatus = result.status() == null ? "" : result.status().strip().toLowerCase(java.util.Locale.ROOT);
        if ("pending".equals(providerStatus) || "ongoing".equals(providerStatus)
                || "processing".equals(providerStatus) || "queued".equals(providerStatus)
                || "abandoned".equals(providerStatus)) {
            return intent;
        }
        if (!"success".equals(providerStatus) && !"failed".equals(providerStatus)) {
            intent.markDisputed();
            intent = paymentIntentRepository.save(intent);
            auditService.recordPaymentDisputed(intent);
            return intent;
        }
        if ("failed".equals(providerStatus)) {
            intent.markFailed();
            intent = paymentIntentRepository.save(intent);
            auditService.recordProviderResult(intent, PaymentAuditEventType.PROVIDER_FAILED);
            resetBookingIfNeeded(intent);
            return intent;
        }

        intent.markSucceeded(Instant.now());
        intent = paymentIntentRepository.save(intent);
        auditService.recordProviderResult(intent, PaymentAuditEventType.PROVIDER_SUCCEEDED);
        entitlementService.activateForSuccessfulPayment(intent);
        settleBookingIfNeeded(intent);
        return intent;
    }

    @Transactional
    public void handleWebhook(String signature, byte[] rawBody) {
        verifySignature(signature, rawBody);
        JsonNode payload;
        try {
            payload = jsonMapper.readTree(rawBody);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook JSON.");
        }
        String event = text(payload, "event");
        if (event!=null && java.util.List.of("transfer.success","transfer.failed","transfer.reversed").contains(event)) {
            var service=payouts.getIfAvailable();
            if(service!=null) service.reconcileReference(text(payload.path("data"),"reference"));
            return;
        }
        if (!"charge.success".equals(event)) {
            return;
        }
        JsonNode data = payload.path("data");
        String reference = text(data, "reference");
        if (reference == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook reference is missing.");
        }
        // Verify with Paystack before any local state transition.
        PaymentIntent intent = paymentIntentRepository.findByProviderReference(reference).orElse(null);
        if (intent == null) {
            return;
        }
        reconcile(reference);
        String eventKey = event + ":" + text(data, "id", reference);
        receiptRepository.insertIfAbsent(
                UUID.randomUUID(), eventKey, event, reference, Instant.now()
        );
    }

    private void verifySignature(String signature, byte[] body) {
        if (signature == null || signature.isBlank() || webhookSecret == null || webhookSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = HexFormat.of().parseHex(signature.strip());
            byte[] actual = mac.doFinal(body == null ? new byte[0] : body);
            if (!MessageDigest.isEqual(actual, expected)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature.");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature.");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Webhook signature validation failed.", exception);
        }
    }

    private void settleBookingIfNeeded(PaymentIntent intent) {
        if (intent.getBookingId() == null) return;
        Booking booking = bookingRepository.findByIdForUpdate(intent.getBookingId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "The booking for this payment no longer exists."
                ));
        if (booking.getStatus() == BookingStatus.PAYMENT_PENDING) {
            booking.markPaidHeld();
            bookingRepository.save(booking);
            auditService.recordBookingPaidHeld(intent, booking);
        }
        escrowLedgerService.recordBookingHold(intent, booking);
    }

    private void resetBookingIfNeeded(PaymentIntent intent) {
        if (intent.getBookingId() == null) return;
        bookingRepository.findByIdForUpdate(intent.getBookingId()).ifPresent(booking -> {
            if (booking.getStatus() == BookingStatus.PAYMENT_PENDING) {
                booking.paymentFailed();
                bookingRepository.save(booking);
            }
        });
    }

    private static String normalizeReference(String value) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid payment reference is required.");
        }
        return value.strip();
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, null);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? fallback
                : value.asText().strip();
    }
}
