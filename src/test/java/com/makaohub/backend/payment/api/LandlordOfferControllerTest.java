package com.makaohub.backend.payment.api;

import com.makaohub.backend.payment.service.LandlordOfferService;
import com.makaohub.backend.payment.service.PaymentIntentService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LandlordOfferControllerTest {
    @Test
    void renterTokenCannotCreateOrReadLandlordOffers() {
        LandlordOfferService offers = mock(LandlordOfferService.class);
        PaymentIntentService payments = mock(PaymentIntentService.class);
        LandlordOfferController controller = new LandlordOfferController(offers, payments);
        Jwt renter = Jwt.withTokenValue("synthetic").header("alg", "none")
                .subject(UUID.randomUUID().toString()).claim("roles", List.of("RENTER")).build();
        assertThrows(ResponseStatusException.class, () -> controller.catalogue(renter));
        assertThrows(ResponseStatusException.class, () -> controller.cardMetrics(renter));
        assertThrows(ResponseStatusException.class, () -> controller.checkout(renter,
                UUID.randomUUID().toString(), new LandlordOfferController.Purchase("PRO_30D", null)));
        verify(payments, never()).create(any());
    }
}
