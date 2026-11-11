package com.makaohub.backend.payment.api;

import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.payment.service.LandlordOffer;
import com.makaohub.backend.payment.service.LandlordOfferService;
import com.makaohub.backend.payment.service.PaymentIntentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SublessorOfferControllerTest {
    @Test
    void rejectsOtherRolesBeforePaymentCreation() {
        var offers = mock(LandlordOfferService.class);
        var payments = mock(PaymentIntentService.class);
        var controller = new SublessorOfferController(offers, payments);
        for (String role : List.of("LANDLORD", "RENTER", "ADMIN")) {
            Jwt token = token(role);
            assertThrows(ResponseStatusException.class, () -> controller.catalogue(token));
            assertThrows(ResponseStatusException.class, () -> controller.cardMetrics(token));
            assertThrows(ResponseStatusException.class, () -> controller.checkout(token,
                    UUID.randomUUID().toString(), new LandlordOfferController.Purchase("SUB_PRO_30D", null)));
        }
        verifyNoInteractions(payments);
    }

    @Test
    void boostUsesServerPriceAndSublessorListingTarget() {
        var offers = mock(LandlordOfferService.class);
        var payments = mock(PaymentIntentService.class);
        var controller = new SublessorOfferController(offers, payments);
        Jwt token = token("SUBLESSOR");
        UUID listing = UUID.randomUUID();
        when(offers.requireSublessorOffer(eq(UUID.fromString(token.getSubject())), eq("SUB_BOOST_7D"), eq(listing)))
                .thenReturn(LandlordOffer.BOOST_7D);
        when(payments.create(any())).thenReturn(mock(PaymentIntent.class));
        controller.checkout(token, UUID.randomUUID().toString(),
                new LandlordOfferController.Purchase("SUB_BOOST_7D", listing));
        var command = ArgumentCaptor.forClass(PaymentIntentService.CreatePaymentCommand.class);
        verify(payments).create(command.capture());
        assertEquals(29900L, command.getValue().amountMinor());
        assertEquals(PaymentTargetKind.SUBLESSOR_LISTING, command.getValue().targetKind());
        assertEquals("SUB_BOOST_7D", command.getValue().offerCode());
    }

    private static Jwt token(String role) {
        return Jwt.withTokenValue("synthetic").header("alg", "none")
                .subject(UUID.randomUUID().toString()).claim("roles", List.of(role)).build();
    }
}
