package com.makaohub.backend.payment.api;

import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.service.LandlordOffer;
import com.makaohub.backend.payment.service.LandlordOfferService;
import com.makaohub.backend.payment.service.PaymentIntentService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Sublessor-only TEST offers; pricing and listing ownership are decided by the server. */
@RestController
@Profile("paystack")
@RequestMapping("/api/v1/sublessor/offers")
public class SublessorOfferController {
    private final LandlordOfferService offers;
    private final PaymentIntentService payments;

    public SublessorOfferController(LandlordOfferService offers, PaymentIntentService payments) {
        this.offers = offers;
        this.payments = payments;
    }

    @GetMapping
    public LandlordOfferController.Catalogue catalogue(@AuthenticationPrincipal Jwt jwt) {
        UUID owner = sublessor(jwt);
        return new LandlordOfferController.Catalogue(1, offers.activeSublessorListingLimit(owner),
                Arrays.stream(LandlordOffer.values()).map(value ->
                        new LandlordOfferController.Offer("SUB_" + value.name(), value.amountMinor,
                                value.duration.toDays(), value.listingLimit)).toList(),
                offers.activeSublessorGrants(owner));
    }

    @GetMapping("/insights")
    public List<LandlordOfferService.ListingInsight> insights(@AuthenticationPrincipal Jwt jwt) {
        return offers.sublessorInsights(sublessor(jwt));
    }

    @GetMapping("/listing-card-metrics")
    public List<LandlordOfferService.ListingCardMetrics> cardMetrics(@AuthenticationPrincipal Jwt jwt) {
        return offers.cardMetrics(sublessor(jwt), true);
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentIntentResponse checkout(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody LandlordOfferController.Purchase request) {
        UUID owner = sublessor(jwt);
        LandlordOffer offer = offers.requireSublessorOffer(owner, request.code(), request.listingId());
        PaymentTargetKind target = offer.paymentType == PaymentType.BOOST
                ? PaymentTargetKind.SUBLESSOR_LISTING : PaymentTargetKind.NONE;
        PaymentIntent intent = payments.create(new PaymentIntentService.CreatePaymentCommand(
                owner, offer.paymentType, request.listingId(), null, target,
                offer.amountMinor, PaymentIntent.KES, key, request.code()));
        return PaymentIntentResponse.from(intent);
    }

    private static UUID sublessor(Jwt jwt) {
        List<String> roles = jwt == null ? null : jwt.getClaimAsStringList("roles");
        if (roles == null || roles.size() != 1 || !"SUBLESSOR".equals(roles.getFirst())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active sublessor role required.");
        }
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid account required.");
        }
    }
}
