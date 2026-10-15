package com.makaohub.backend.payment.service;

import com.makaohub.backend.payment.audit.domain.PaymentAuditEventType;
import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.payment.entitlement.service.ListingUnlockEntitlementService;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.verification.service.RoleVerificationGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Owns idempotent payment-intent creation and local mock completion. */
@Service
public class PaymentIntentService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentProvider paymentProvider;
    private final MockPaymentAuditService auditService;
    private final ListingUnlockEntitlementService entitlementService;
    private final RoleVerificationGuard verificationGuard;

    public PaymentIntentService(
            PaymentIntentRepository paymentIntentRepository,
            PaymentProvider paymentProvider,
            MockPaymentAuditService auditService,
            ListingUnlockEntitlementService entitlementService,
            RoleVerificationGuard verificationGuard
    ) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentProvider = paymentProvider;
        this.auditService = auditService;
        this.entitlementService = entitlementService;
        this.verificationGuard = verificationGuard;
    }

    @Transactional
    public PaymentIntent create(CreatePaymentCommand command) {
        Objects.requireNonNull(command, "Payment command is required.");
        if (requiresVerifiedRenter(command.paymentType())) {
            verificationGuard.requireVerified(command.payerUserId(), RoleName.RENTER);
        }

        var existing = paymentIntentRepository
                .findByPayerUserIdAndIdempotencyKey(
                        command.payerUserId(), command.idempotencyKey()
                );
        if (existing.isPresent()) {
            PaymentIntent intent = existing.get();
            if (!matches(intent, command)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The idempotency key was already used for a different payment."
                );
            }
            // A provider failure can happen after the CREATED row is committed
            // but before the provider reference is stored.  The same
            // idempotency key may safely retry that provider start; once the
            // intent is PENDING or terminal, it is returned unchanged.
            if (intent.getStatus() == PaymentStatus.CREATED) {
                intent.markPending(paymentProvider.start(intent));
                return paymentIntentRepository.save(intent);
            }
            return intent;
        }

        // The amount must come from trusted server-side pricing logic.
        PaymentIntent intent = new PaymentIntent(
                command.payerUserId(), command.paymentType(), command.listingId(),
                command.bookingId(), command.targetKind(), command.amountMinor(),
                command.currency(),
                command.idempotencyKey()
        );
        intent = paymentIntentRepository.save(intent);
        intent.markPending(paymentProvider.start(intent));
        intent = paymentIntentRepository.save(intent);
        auditService.recordIntentCreated(intent);
        return intent;
    }

    @Transactional
    public PaymentIntent completeMockCallback(
            UUID payerUserId,
            String providerReference,
            MockPaymentOutcome outcome,
            Instant completedAt
    ) {
        if (providerReference == null || providerReference.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Provider reference is required."
            );
        }
        Objects.requireNonNull(outcome, "Mock outcome is required.");
        Objects.requireNonNull(completedAt, "Completion time is required.");
        Objects.requireNonNull(payerUserId, "Payer user ID is required.");

        PaymentIntent intent = paymentIntentRepository
                .findByPayerUserIdAndProviderReferenceForUpdate(
                        payerUserId,
                        providerReference.strip()
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Payment intent was not found."
                ));

        if (intent.getStatus() == PaymentStatus.SUCCEEDED
                && outcome == MockPaymentOutcome.SUCCESS) {
            return intent;
        }
        if (intent.getStatus() == PaymentStatus.FAILED
                && outcome == MockPaymentOutcome.FAILED) {
            return intent;
        }
        if (intent.getStatus() != PaymentStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payment intent is not awaiting a provider result."
            );
        }

        if (outcome == MockPaymentOutcome.SUCCESS) {
            intent.markSucceeded(completedAt);
            intent = paymentIntentRepository.save(intent);
            auditService.recordProviderResult(
                    intent, PaymentAuditEventType.PROVIDER_SUCCEEDED
            );
            entitlementService.activateForSuccessfulPayment(intent);
        } else {
            intent.markFailed();
            intent = paymentIntentRepository.save(intent);
            auditService.recordProviderResult(
                    intent, PaymentAuditEventType.PROVIDER_FAILED
            );
        }
        return intent;
    }

    private boolean matches(PaymentIntent intent, CreatePaymentCommand command) {
        return intent.getPaymentType() == command.paymentType()
                && intent.getTargetKind() == command.targetKind()
                && Objects.equals(intent.getListingId(), command.listingId())
                && Objects.equals(intent.getBookingId(), command.bookingId())
                && intent.getAmountMinor() == command.amountMinor()
                && intent.getCurrency().equalsIgnoreCase(command.currency());
    }

    private static boolean requiresVerifiedRenter(PaymentType paymentType) {
        return switch (paymentType) {
            case BOOKING, EXACT_LOCATION_UNLOCK, VIDEO_UNLOCK, PHONE_UNLOCK -> true;
            default -> false;
        };
    }

    public record CreatePaymentCommand(
            UUID payerUserId,
            PaymentType paymentType,
            UUID listingId,
            UUID bookingId,
            PaymentTargetKind targetKind,
            long amountMinor,
            String currency,
            String idempotencyKey
    ) {
        public CreatePaymentCommand {
            Objects.requireNonNull(payerUserId, "Payer user ID is required.");
            Objects.requireNonNull(paymentType, "Payment type is required.");
            Objects.requireNonNull(targetKind, "Payment target kind is required.");
            Objects.requireNonNull(currency, "Currency is required.");
            Objects.requireNonNull(idempotencyKey, "Idempotency key is required.");
            if (amountMinor <= 0) {
                throw new IllegalArgumentException("Payment amount must be positive.");
            }
            idempotencyKey = idempotencyKey.strip();
            if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
                throw new IllegalArgumentException(
                        "Idempotency key must contain 1 to 100 characters."
                );
            }
        }
    }
}
