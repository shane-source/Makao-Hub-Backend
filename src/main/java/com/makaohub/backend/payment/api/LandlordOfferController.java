package com.makaohub.backend.payment.api;

import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.service.LandlordOffer;
import com.makaohub.backend.payment.service.LandlordOfferService;
import com.makaohub.backend.payment.service.PaymentIntentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Test-mode landlord offers; no amount or role is accepted from the client. */
@RestController
@Profile("paystack")
@RequestMapping("/api/v1/landlord/offers")
public class LandlordOfferController {
    private final LandlordOfferService offers;
    private final PaymentIntentService payments;

    public LandlordOfferController(LandlordOfferService offers, PaymentIntentService payments) {
        this.offers = offers;
        this.payments = payments;
    }

    @GetMapping
    public Catalogue catalogue(@AuthenticationPrincipal Jwt jwt) {
        UUID landlordId = landlord(jwt);
        return new Catalogue(1, offers.activeListingLimit(landlordId),
                Arrays.stream(LandlordOffer.values()).map(value ->
                        new Offer(value.name(), value.amountMinor, value.duration.toDays(),
                                value.listingLimit, value.estimatedReach, publicPhotoLimit(value.photoLimit),
                                value.includedBoosts)).toList(), offers.activeGrants(landlordId),
                LandlordOffer.FREE_PHOTO_LIMIT, publicPhotoLimit(offers.photoLimit(landlordId, false)),
                offers.boostCredits(landlordId, false));
    }

    /** Uses the boost included with an active Pro Max plan on one published listing. */
    @PostMapping("/boost-credit/redeem")
    public RedeemResult redeemIncludedBoost(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RedeemRequest request) {
        return new RedeemResult(offers.redeemIncludedBoost(landlord(jwt), request.listingId(), false));
    }

    @GetMapping("/insights")
    public List<LandlordOfferService.ListingInsight> insights(@AuthenticationPrincipal Jwt jwt) {
        return offers.insights(landlord(jwt));
    }

    @GetMapping("/listing-card-metrics")
    public List<LandlordOfferService.ListingCardMetrics> cardMetrics(@AuthenticationPrincipal Jwt jwt) {
        return offers.cardMetrics(landlord(jwt), false);
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentIntentResponse checkout(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Purchase request) {
        UUID landlordId = landlord(jwt);
        LandlordOffer offer = offers.requireOffer(landlordId, request.code(), request.listingId());
        PaymentTargetKind target = offer.paymentType == PaymentType.BOOST
                ? PaymentTargetKind.LANDLORD_LISTING : PaymentTargetKind.NONE;
        PaymentIntent intent = payments.create(new PaymentIntentService.CreatePaymentCommand(
                landlordId, offer.paymentType, request.listingId(), null, target,
                offer.amountMinor, PaymentIntent.KES, key, offer.name()));
        return PaymentIntentResponse.from(intent);
    }

    private static UUID landlord(Jwt jwt) {
        List<String> roles = jwt == null ? null : jwt.getClaimAsStringList("roles");
        if (roles == null || roles.size() != 1 || !"LANDLORD".equals(roles.getFirst())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active landlord role required.");
        }
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid account required.");
        }
    }

    static int publicPhotoLimit(int limit) { return limit == Integer.MAX_VALUE ? -1 : limit; }

    public record RedeemRequest(@jakarta.validation.constraints.NotNull UUID listingId) {}
    public record RedeemResult(java.time.Instant boostExpiresAt) {}
    public record Purchase(@NotBlank @Size(max = 20) String code, UUID listingId) {}
    public record Offer(String code, long amountMinor, long days, int listingLimit, int estimatedReach,
            int photoLimit, int includedBoosts) {}
    /** photoLimit values of -1 mean unlimited. */
    public record Catalogue(int starterLimit, int activeListingLimit, List<Offer> offers,
            List<LandlordOfferService.ActiveGrant> activeGrants, int freePhotoLimit, int activePhotoLimit,
            int boostCredits) {}
}
