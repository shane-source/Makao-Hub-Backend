package com.makaohub.backend.payment.api;

import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.shared.api.PageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
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

@ExtendWith(MockitoExtension.class)
class PaymentQueryControllerTest {

    @Mock
    private PaymentIntentRepository repository;

    @Test
    void statusOnlyReadsAnIntentOwnedByTheAuthenticatedUser() {
        UUID userId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(intentId);
        when(intent.getCreatedAt()).thenReturn(Instant.now());
        when(repository.findByIdAndPayerUserId(intentId, userId))
                .thenReturn(Optional.of(intent));

        PaymentIntentResponse response = new PaymentQueryController(repository)
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
                new PaymentQueryController(repository)
                        .history(jwt(userId, "LANDLORD"), 0, 20);

        assertEquals(0, response.totalElements());
        verify(repository).findAllByPayerUserId(any(), any());
    }

    @Test
    void adminCannotUseTheCustomerPaymentHistoryEndpoint() {
        assertThrows(ResponseStatusException.class, () ->
                new PaymentQueryController(repository)
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
