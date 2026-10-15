package com.makaohub.backend.renter.api;

import com.makaohub.backend.payment.api.MockPaymentCompletionRequest;
import com.makaohub.backend.payment.api.PaymentIntentResponse;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.service.BookingCreationService;
import com.makaohub.backend.renter.service.BookingPaymentService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/** Renter-only booking creation and mock checkout endpoints. */
@RestController
@Profile("mock")
@RequestMapping("/api/v1/renter/bookings")
public class RenterBookingController {

    private final BookingCreationService bookingCreationService;
    private final BookingPaymentService bookingPaymentService;

    public RenterBookingController(
            BookingCreationService bookingCreationService,
            BookingPaymentService bookingPaymentService
    ) {
        this.bookingCreationService = bookingCreationService;
        this.bookingPaymentService = bookingPaymentService;
    }

    @PostMapping("/landlord-applications/{applicationId}")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createFromLandlordApplication(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId
    ) {
        return BookingResponse.from(bookingCreationService
                .createForLandlordApplication(userId(jwt), applicationId));
    }

    @PostMapping("/sublessor-applications/{applicationId}")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createFromSublessorApplication(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId
    ) {
        return BookingResponse.from(bookingCreationService
                .createForSublessorApplication(userId(jwt), applicationId));
    }

    @PostMapping("/{bookingId}/payment-intent")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentIntentResponse createPaymentIntent(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        PaymentIntent intent = bookingPaymentService.createPaymentIntent(
                userId(jwt), bookingId, idempotencyKey
        );
        return PaymentIntentResponse.from(intent);
    }

    @PostMapping("/mock-payment-completions")
    public PaymentIntentResponse completeMockPayment(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MockPaymentCompletionRequest request
    ) {
        PaymentIntent intent = bookingPaymentService.completeMockPayment(
                userId(jwt),
                request.providerReference(),
                request.outcome(),
                Instant.now()
        );
        return PaymentIntentResponse.from(intent);
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
