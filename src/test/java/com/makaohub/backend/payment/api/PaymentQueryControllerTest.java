package com.makaohub.backend.payment.api;

import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.payment.service.PaymentPricingService;
import com.makaohub.backend.shared.api.PageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentQueryControllerTest {

    @Mock
    private PaymentIntentRepository repository;

    private PaymentQueryController controller() {
        return new PaymentQueryController(repository, new PaymentPricingService(
                new MockEnvironment()
                        .withProperty("makao.payment.prices.exact-location-unlock-minor", "32100")
                        .withProperty("makao.payment.prices.video-unlock-minor", "12300")
                        .withProperty("makao.payment.prices.phone-unlock-minor", "4500")
        ));
    }

    @Test
    void renterCanReadServerOwnedUnlockPriceInEveryPaymentProfile() {
        PaymentQueryController.UnlockPrice quote = controller().price(
                jwt(UUID.randomUUID(), "RENTER"), PaymentType.EXACT_LOCATION_UNLOCK
        );

        assertEquals(32100, quote.amountMinor());
        assertEquals("KES", quote.currency());
        assertThrows(ResponseStatusException.class, () -> controller().price(
                jwt(UUID.randomUUID(), "LANDLORD"), PaymentType.EXACT_LOCATION_UNLOCK
        ));
        assertThrows(ResponseStatusException.class, () -> controller().price(
                jwt(UUID.randomUUID(), "RENTER"), PaymentType.BOOKING
        ));
    }

    @Test
    void statusOnlyReadsAnIntentOwnedByTheAuthenticatedUser() {
        UUID userId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(intentId);
        when(intent.getCreatedAt()).thenReturn(Instant.now());
        when(repository.findByIdAndPayerUserId(intentId, userId))
                .thenReturn(Optional.of(intent));

        PaymentIntentResponse response = controller()
                .status(jwt(userId, "RENTER"), new PaymentStatusRequest(intentId));

        assertEquals(intentId, response.id());
        verify(repository).findByIdAndPayerUserId(intentId, userId);
    }

    @Test
    void historyUsesBoundedPagination() {
        UUID userId = UUID.randomUUID();
        when(repository.findAllByPayerUserId(any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        PageResponse<PaymentIntentResponse> response =
                controller()
                        .history(jwt(userId, "LANDLORD"), 0, 20);

        assertEquals(0, response.totalElements());
        verify(repository).findAllByPayerUserId(any(), any());
    }

    @Test
    void bookingPaymentLookupUsesAuthenticatedPayerAndBookingType() {
        UUID renterId = UUID.randomUUID(), bookingId = UUID.randomUUID(), intentId = UUID.randomUUID();
        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(intentId);
        when(repository.findFirstByPayerUserIdAndBookingIdAndPaymentTypeOrderByCreatedAtDesc(
                renterId, bookingId, PaymentType.BOOKING)).thenReturn(Optional.of(intent));
        assertEquals(intentId, controller().bookingPayment(jwt(renterId, "RENTER"), bookingId).id());
        verify(repository).findFirstByPayerUserIdAndBookingIdAndPaymentTypeOrderByCreatedAtDesc(
                renterId, bookingId, PaymentType.BOOKING);
    }

    @Test
    void anotherRentersBookingPaymentIsNotReturned() {
        UUID renterId = UUID.randomUUID(), bookingId = UUID.randomUUID();
        when(repository.findFirstByPayerUserIdAndBookingIdAndPaymentTypeOrderByCreatedAtDesc(
                renterId, bookingId, PaymentType.BOOKING)).thenReturn(Optional.empty());
        var failure = assertThrows(ResponseStatusException.class,
                () -> controller().bookingPayment(jwt(renterId, "RENTER"), bookingId));
        assertEquals(404, failure.getStatusCode().value());
    }

    @Test
    void onlyRenterRoleCanLookupExistingBookingPayment() {
        for (String role : List.of("LANDLORD", "SUBLESSOR", "ADMIN")) {
            var failure = assertThrows(ResponseStatusException.class,
                    () -> controller().bookingPayment(jwt(UUID.randomUUID(), role), UUID.randomUUID()));
            assertEquals(403, failure.getStatusCode().value());
        }
        verifyNoInteractions(repository);
    }

    @Test
    void adminCannotUseTheCustomerPaymentHistoryEndpoint() {
        assertThrows(ResponseStatusException.class, () ->
                controller()
                        .history(jwt(UUID.randomUUID(), "ADMIN"), 0, 20)
        );
    }

    private static Jwt jwt(UUID userId, String role) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(userId.toString())
                .claim("roles", List.of(role))
                .build();
    }
}
