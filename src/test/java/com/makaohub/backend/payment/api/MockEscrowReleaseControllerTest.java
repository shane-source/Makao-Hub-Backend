package com.makaohub.backend.payment.api;

import com.makaohub.backend.admin.StaffAccessService;
import com.makaohub.backend.admin.StaffRole;
import com.makaohub.backend.payment.paystack.config.PaystackProperties;
import com.makaohub.backend.renter.api.BookingResponse;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.service.BookingAgreementService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MockEscrowReleaseControllerTest {

    @Test
    void paystackTestKeyCannotBypassVerifiedTransferWithAdminSimulation() {
        BookingAgreementService agreementService = mock(BookingAgreementService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("paystack");
        PaystackProperties properties = new PaystackProperties(
                true, "https://api.paystack.co", "sk_test_sandbox"
        );
        UUID adminId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Jwt admin = adminJwt(adminId);
        StaffAccessService staffAccess = mock(StaffAccessService.class);
        when(staffAccess.require(admin, StaffRole.Permission.TEST_RELEASE))
                .thenReturn(new StaffAccessService.Staff(adminId, StaffRole.PAYMENT_REVIEWER, false));
        when(agreementService.releaseMock(eq(adminId), eq(bookingId), eq("release-1"), any()))
                .thenReturn(mock(Booking.class));

        var controller = new MockEscrowReleaseController(
                agreementService, environment, properties, staffAccess
        );
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.release(admin,bookingId,"release-1")).getStatusCode().value());
        verify(agreementService,never()).releaseMock(any(),any(),any(),any());
    }

    @Test
    void paystackLiveKeyCannotUseMockRelease() {
        BookingAgreementService agreementService = mock(BookingAgreementService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("paystack");
        PaystackProperties properties = new PaystackProperties(
                true, "https://api.paystack.co", "sk_live_not-used"
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> new MockEscrowReleaseController(
                        agreementService, environment, properties, mock(StaffAccessService.class)
                ).release(adminJwt(UUID.randomUUID()), UUID.randomUUID(), "release-live")
        );

        assertEquals(409, error.getStatusCode().value());
        verify(agreementService, never()).releaseMock(any(), any(), any(), any());
    }

    @Test
    void disabledPaystackCannotUseMockReleaseEvenWithTestKey() {
        BookingAgreementService agreementService = mock(BookingAgreementService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("paystack");
        PaystackProperties properties = new PaystackProperties(
                false, "https://api.paystack.co", "sk_test_sandbox"
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> new MockEscrowReleaseController(
                        agreementService, environment, properties, mock(StaffAccessService.class)
                ).release(adminJwt(UUID.randomUUID()), UUID.randomUUID(), "release-disabled")
        );

        assertEquals(409, error.getStatusCode().value());
        verify(agreementService, never()).releaseMock(any(), any(), any(), any());
    }

    @Test
    void staffWithoutTestReleasePermissionCannotRelease() {
        BookingAgreementService agreementService = mock(BookingAgreementService.class);
        StaffAccessService staffAccess = mock(StaffAccessService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("paystack");
        Jwt admin = adminJwt(UUID.randomUUID());
        when(staffAccess.require(admin, StaffRole.Permission.TEST_RELEASE))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new MockEscrowReleaseController(agreementService, environment,
                        new PaystackProperties(true, "https://api.paystack.co", "sk_test_sandbox"), staffAccess)
                        .release(admin, UUID.randomUUID(), "blocked-release"));

        assertEquals(403, error.getStatusCode().value());
        verify(agreementService, never()).releaseMock(any(), any(), any(), any());
    }

    private static Jwt adminJwt(UUID adminId) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(adminId.toString())
                .claim("roles", List.of("ADMIN"))
                .build();
    }
}
