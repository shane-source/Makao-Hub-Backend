package com.makaohub.backend.renter.api;

import com.makaohub.backend.renter.service.BookingAgreementService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/** Renter confirmation that the paid booking terms are accepted. */
@RestController
@RequestMapping("/api/v1/renter/bookings")
public class RenterBookingAgreementController {

    private final BookingAgreementService agreementService;

    public RenterBookingAgreementController(BookingAgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @PostMapping("/{bookingId}/agreement/confirm")
    public BookingResponse confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId
    ) {
        return BookingResponse.from(
                agreementService.confirmRenter(userId(jwt), bookingId)
        );
    }

    /** Starts the server-controlled 24-hour release timer after move-in. */
    @PostMapping("/{bookingId}/move-in/confirm")
    public BookingResponse confirmMoveIn(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId
    ) {
        return BookingResponse.from(
                agreementService.confirmMoveIn(userId(jwt), bookingId)
        );
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
}
