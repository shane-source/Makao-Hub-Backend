package com.makaohub.backend.payment.api;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.service.PaymentIntentService;
import com.makaohub.backend.payment.service.PaymentPricingService;
import com.makaohub.backend.renter.service.BookingPaymentService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Local payment endpoints. They exist only with the explicit mock profile. */
@RestController
@Profile("mock")
@RequestMapping("/api/v1/payments")
public class MockPaymentController {

    public record UnlockPrice(String paymentType, long amountMinor, String currency) {}
    @org.springframework.web.bind.annotation.GetMapping("/prices/{type}")
    public UnlockPrice price(@AuthenticationPrincipal Jwt jwt,
            @org.springframework.web.bind.annotation.PathVariable PaymentType type) {
        requireRole(activeRole(jwt), RoleName.RENTER);
        if (!List.of(PaymentType.EXACT_LOCATION_UNLOCK,PaymentType.VIDEO_UNLOCK,PaymentType.PHONE_UNLOCK).contains(type))
            throw badRequest("Only listing unlock prices are available here.");
        return new UnlockPrice(type.name(),pricingService.amountMinor(type),"KES");
    }

    private final PaymentIntentService paymentIntentService;
    private final PaymentPricingService pricingService;
    private final ListingRepository landlordListings;
    private final SublessorListingRepository sublessorListings;
    private final BookingPaymentService bookingPaymentService;
    private final com.makaohub.backend.property.service.RenterHomeService homes;

    public MockPaymentController(
            PaymentIntentService paymentIntentService,
            PaymentPricingService pricingService,
            ListingRepository landlordListings,
            SublessorListingRepository sublessorListings,
            BookingPaymentService bookingPaymentService,
            com.makaohub.backend.property.service.RenterHomeService homes
    ) {
        this.paymentIntentService = paymentIntentService;
        this.pricingService = pricingService;
        this.landlordListings = landlordListings;
        this.sublessorListings = sublessorListings;
        this.bookingPaymentService = bookingPaymentService;
        this.homes = homes;
    }

    @PostMapping("/intents")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentIntentResponse createIntent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request
    ) {
        UUID payerUserId = userId(jwt);
        RoleName role = activeRole(jwt);
        if (request.paymentType() == PaymentType.BOOKING) {
            requireRole(role, RoleName.RENTER);
            if (request.listingId() != null || request.bookingId() == null) {
                throw badRequest("A booking is required for this payment.");
            }
            return PaymentIntentResponse.from(
                    bookingPaymentService.createPaymentIntent(
                            payerUserId,
                            request.bookingId(),
                            idempotencyKey
                    )
            );
        }
        if (request.bookingId() != null) {
            throw badRequest("A booking can only use the BOOKING payment type.");
        }
        PaymentTargetKind targetKind = resolveTarget(
                payerUserId,
                role,
                request.paymentType(),
                request.listingId()
        );

        try {
            PaymentIntent intent = paymentIntentService.create(
                    new PaymentIntentService.CreatePaymentCommand(
                            payerUserId,
                            request.paymentType(),
                            request.listingId(),
                            null,
                            targetKind,
                            pricingService.amountMinor(request.paymentType()),
                            PaymentIntent.KES,
                            idempotencyKey
                    )
            );
            return PaymentIntentResponse.from(intent);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage()
            );
        }
    }

    @PostMapping("/mock/completions")
    public PaymentIntentResponse completeMockPayment(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MockPaymentCompletionRequest request
    ) {
        UUID payerUserId = userId(jwt);
        PaymentIntent intent = activeRole(jwt) == RoleName.RENTER
                ? bookingPaymentService.completeMockPayment(
                        payerUserId,
                        request.providerReference(),
                        request.outcome(),
                        Instant.now()
                )
                : paymentIntentService.completeMockCallback(
                        payerUserId,
                        request.providerReference(),
                        request.outcome(),
                        Instant.now()
                );
        return PaymentIntentResponse.from(intent);
    }

    private PaymentTargetKind resolveTarget(
            UUID payerUserId,
            RoleName role,
            PaymentType paymentType,
            UUID listingId
    ) {
        if (paymentType == PaymentType.BOOKING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Booking payments require the booking flow."
            );
        }

        if (paymentType == PaymentType.EXACT_LOCATION_UNLOCK
                || paymentType == PaymentType.VIDEO_UNLOCK
                || paymentType == PaymentType.PHONE_UNLOCK) {
            requireRole(role, RoleName.RENTER);
            if (listingId == null) {
                throw badRequest("A listing is required for this payment.");
            }
            homes.access(payerUserId, listingId);
            return resolvePublishedListing(listingId);
        }

        requireOwnerPaymentRole(role);
        if (paymentType == PaymentType.PLAN_UPGRADE) {
            if (listingId != null) {
                throw badRequest("A plan payment cannot target a listing.");
            }
            return PaymentTargetKind.NONE;
        }

        if (listingId == null) {
            throw badRequest("A listing is required for this payment.");
        }
        return resolveOwnedListing(payerUserId, role, listingId);
    }

    private PaymentTargetKind resolvePublishedListing(UUID listingId) {
        boolean landlordListing = landlordListings
                .findByIdAndStatus(listingId, ListingStatus.PUBLISHED)
                .isPresent();
        boolean sublessorListing = sublessorListings
                .findById(listingId)
                .map(listing -> listing.getStatus() == ListingStatus.PUBLISHED)
                .orElse(false);

        if (landlordListing && sublessorListing) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The listing identifier is ambiguous."
            );
        }
        if (landlordListing) {
            return PaymentTargetKind.LANDLORD_LISTING;
        }
        if (sublessorListing) {
            return PaymentTargetKind.SUBLESSOR_LISTING;
        }
        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Published listing not found."
        );
    }

    private PaymentTargetKind resolveOwnedListing(
            UUID payerUserId,
            RoleName role,
            UUID listingId
    ) {
        if (role == RoleName.LANDLORD
                && landlordListings.findByIdAndLandlordId(
                listingId,
                payerUserId
        ).isPresent()) {
            return PaymentTargetKind.LANDLORD_LISTING;
        }
        if (role == RoleName.SUBLESSOR
                && sublessorListings.findByIdAndSublessorId(
                listingId,
                payerUserId
        ).isPresent()) {
            return PaymentTargetKind.SUBLESSOR_LISTING;
        }
        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Owned listing not found."
        );
    }

    private static void requireOwnerPaymentRole(RoleName role) {
        if (role != RoleName.LANDLORD && role != RoleName.SUBLESSOR) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only landlords and sublessors can buy owner payments."
            );
        }
    }

    private static void requireRole(RoleName actual, RoleName expected) {
        if (actual != expected) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "The active role cannot make this payment."
            );
        }
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

    private static RoleName activeRole(Jwt jwt) {
        List<String> roles = jwt == null
                ? null
                : jwt.getClaimAsStringList("roles");
        if (roles == null || roles.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "The access token has no active role."
            );
        }
        try {
            return RoleName.valueOf(roles.getFirst());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "The access token has an invalid role."
            );
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
