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

/** Landlord/Sublessor confirmation of the same paid booking terms. */
@RestController
@RequestMapping({
        "/api/v1/landlord/bookings",
        "/api/v1/sublessor/bookings"
})
public class OwnerBookingAgreementController {

    private final BookingAgreementService agreementService;

    public OwnerBookingAgreementController(BookingAgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @PostMapping("/{bookingId}/agreement/confirm")
    public BookingResponse confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId
    ) {
        return BookingResponse.from(
                agreementService.confirmOwner(userId(jwt), bookingId)
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
