package com.makaohub.backend.payment.api;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.service.*;
import com.makaohub.backend.property.service.RenterHomeService;
import com.makaohub.backend.property.repository.*;
import com.makaohub.backend.renter.service.BookingPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RenterUnlockCheckoutTest {
    private final PaymentIntentService payments=mock(PaymentIntentService.class);
    private final RenterHomeService homes=mock(RenterHomeService.class);
    private final MockPaymentController controller=new MockPaymentController(payments,
        new PaymentPricingService(new MockEnvironment().withProperty("makao.payment.prices.exact-location-unlock-minor","32100")),
        mock(ListingRepository.class),mock(SublessorListingRepository.class),mock(BookingPaymentService.class),homes);
    private Jwt actor(String role) { return Jwt.withTokenValue("synthetic").header("alg","RS256").subject(UUID.randomUUID().toString()).claim("roles",List.of(role)).build(); }
    @Test void quoteUsesConfiguredIntegerMinorUnitsAndNoPaymentIsCreated() {
        assertEquals(32100,controller.price(actor("RENTER"),PaymentType.EXACT_LOCATION_UNLOCK).amountMinor());
        verifyNoInteractions(payments,homes);
        assertThrows(ResponseStatusException.class,()->controller.price(actor("LANDLORD"),PaymentType.EXACT_LOCATION_UNLOCK));
        assertThrows(ResponseStatusException.class,()->controller.price(actor("RENTER"),PaymentType.BOOKING));
    }
    @Test void deniedHomeVerificationStopsBeforeAnyMoneyOperation() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(homes).access(any(),any());
        assertThrows(ResponseStatusException.class,()->controller.createIntent(actor("RENTER"),"synthetic-idempotency",
            new CreatePaymentIntentRequest(PaymentType.EXACT_LOCATION_UNLOCK,UUID.randomUUID(),null)));
        verifyNoInteractions(payments);
    }
}
