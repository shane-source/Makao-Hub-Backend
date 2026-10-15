package com.makaohub.backend.payment.api;

import com.makaohub.backend.renter.api.BookingResponse;
import com.makaohub.backend.renter.service.BookingAgreementService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.UUID;
import java.util.List;
import java.time.Instant;
import com.makaohub.backend.auth.domain.RoleName;

/**
 * Test-only admin release; it records a payout simulation and moves no money.
 * It is available with either the mock or Paystack test profile so the full
 * collection-to-release journey can be exercised without real payouts.
 */
@RestController
@Profile({"mock", "paystack"})
@RequestMapping("/api/v1/admin/payments/bookings")
public class MockEscrowReleaseController {

    private final BookingAgreementService agreementService;

    public MockEscrowReleaseController(BookingAgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @PostMapping("/{bookingId}/release")
    public BookingResponse release(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireActiveAdminRole(jwt);
        return BookingResponse.from(
                agreementService.releaseMock(
                        userId(jwt),
                        bookingId,
                        idempotencyKey,
                        Instant.now()
                )
        );
    }

    private static void requireActiveAdminRole(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "A valid access token is required."
            );
        }
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.size() != 1
                || !RoleName.ADMIN.name().equals(roles.getFirst())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "An active admin role is required."
            );
        }
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
