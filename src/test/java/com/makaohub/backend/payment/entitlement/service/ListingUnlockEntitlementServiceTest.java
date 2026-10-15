package com.makaohub.backend.payment.entitlement.service;

import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.entitlement.domain.ListingUnlockEntitlement;
import com.makaohub.backend.payment.entitlement.repository.ListingUnlockEntitlementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingUnlockEntitlementServiceTest {

    @Mock private ListingUnlockEntitlementRepository repository;

    @Test
    void activatesSuccessfulLocationUnlockOnlyOnce() {
        UUID paymentId = UUID.randomUUID();
        UUID payerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(paymentId);
        when(intent.getPayerUserId()).thenReturn(payerId);
        when(intent.getListingId()).thenReturn(listingId);
        when(intent.getPaymentType()).thenReturn(PaymentType.EXACT_LOCATION_UNLOCK);
        when(intent.getStatus()).thenReturn(PaymentStatus.SUCCEEDED);
        when(intent.getAmountMinor()).thenReturn(50L);
        when(intent.getCurrency()).thenReturn("KES");
        when(repository.findByPaymentIntentId(paymentId))
                .thenReturn(Optional.empty(), Optional.of(org.mockito.Mockito.mock(ListingUnlockEntitlement.class)));

        ListingUnlockEntitlementService service =
                new ListingUnlockEntitlementService(repository);
        service.activateForSuccessfulPayment(intent);
        service.activateForSuccessfulPayment(intent);

        ArgumentCaptor<ListingUnlockEntitlement> captor =
                ArgumentCaptor.forClass(ListingUnlockEntitlement.class);
        verify(repository, times(1)).save(captor.capture());
        assertEquals(payerId, captor.getValue().getPayerUserId());
        assertEquals(listingId, captor.getValue().getListingId());
    }

    @Test
    void ignoresFailedPayments() {
        PaymentIntent intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getStatus()).thenReturn(PaymentStatus.FAILED);

        new ListingUnlockEntitlementService(repository)
                .activateForSuccessfulPayment(intent);

        verify(repository, times(0)).save(any(ListingUnlockEntitlement.class));
    }
}
