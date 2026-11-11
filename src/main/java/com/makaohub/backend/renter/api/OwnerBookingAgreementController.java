package com.makaohub.backend.renter.api;

import com.makaohub.backend.renter.service.BookingAgreementService;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.renter.domain.Booking;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Landlord/Sublessor confirmation of the same paid booking terms. */
@RestController
@RequestMapping({
        "/api/v1/landlord/bookings",
        "/api/v1/sublessor/bookings"
})
public class OwnerBookingAgreementController {

    private final BookingAgreementService agreementService;
    private final UserRepository users;

    public OwnerBookingAgreementController(BookingAgreementService agreementService, UserRepository users) {
        this.agreementService = agreementService;
        this.users = users;
    }

    /** Owner-scoped booking summary for payout status and review requests. */
    @GetMapping
    public List<BookingResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return responses(agreementService.ownerBookings(userId(jwt), ownerRole(jwt)));
    }

    /** Requests staff review only; never initiates a Paystack transfer. */
    @PostMapping("/{bookingId}/release-review-request")
    public BookingResponse requestReleaseReview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return responses(List.of(agreementService.requestOwnerReleaseReview(
                userId(jwt), bookingId, ownerRole(jwt), idempotencyKey
        ))).getFirst();
    }

    @PostMapping("/{bookingId}/move-in/confirm")
    public BookingResponse confirmMoveIn(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID bookingId,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return responses(List.of(agreementService.confirmOwnerMoveIn(userId(jwt), bookingId, ownerRole(jwt), idempotencyKey))).getFirst();
    }

    @PostMapping("/{bookingId}/agreement/confirm")
    public BookingResponse confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId
    ) {
        return responses(List.of(agreementService.confirmOwner(userId(jwt), bookingId, ownerRole(jwt)))).getFirst();
    }

    private List<BookingResponse> responses(List<Booking> bookings) {
        if (bookings.isEmpty()) return List.of();
        Map<UUID, String> names = users.findAllById(bookings.stream()
                        .flatMap(booking -> Stream.of(booking.getRenterId(), booking.getOwnerId()))
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        return bookings.stream().map(booking -> BookingResponse.from(booking,
                names.get(booking.getRenterId()), names.get(booking.getOwnerId()))).toList();
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

    private static RoleName ownerRole(Jwt jwt) {
        List<String> roles = jwt == null ? null : jwt.getClaimAsStringList("roles");
        if (roles == null || roles.size() != 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return switch (roles.getFirst()) {
            case "LANDLORD" -> RoleName.LANDLORD;
            case "SUBLESSOR" -> RoleName.SUBLESSOR;
            default -> throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        };
    }
}
