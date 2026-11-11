package com.makaohub.backend.payment.api;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.admin.StaffAccessService;
import com.makaohub.backend.admin.StaffRole;
import com.makaohub.backend.payment.paystack.config.PaystackProperties;
import com.makaohub.backend.renter.api.BookingResponse;
import com.makaohub.backend.renter.service.BookingAgreementService;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    private final Environment environment;
    private final PaystackProperties paystackProperties;
    private final StaffAccessService staffAccess;

    public MockEscrowReleaseController(
            BookingAgreementService agreementService,
            Environment environment,
            PaystackProperties paystackProperties,
            StaffAccessService staffAccess
    ) {
        this.agreementService = agreementService;
        this.environment = environment;
        this.paystackProperties = paystackProperties;
        this.staffAccess = staffAccess;
    }

    @PostMapping("/{bookingId}/release")
    public BookingResponse release(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requireActiveAdminRole(jwt);
        staffAccess.require(jwt, StaffRole.Permission.TEST_RELEASE);
        requireTestOnlySimulation();
        return BookingResponse.from(
                agreementService.releaseMock(
                        userId(jwt),
                        bookingId,
                        idempotencyKey,
                        Instant.now()
                )
        );
    }

    private void requireTestOnlySimulation() {
        if (environment.acceptsProfiles(Profiles.of("paystack"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Paystack TEST bookings use automatic verified transfers; manual simulated release is disabled.");
        }
        if (environment.acceptsProfiles(Profiles.of("paystack"))
                && (!paystackProperties.enabled()
                || paystackProperties.secretKey() == null
                || !paystackProperties.secretKey().startsWith("sk_test_"))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Simulated release is available only with an enabled Paystack test key."
            );
        }
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
