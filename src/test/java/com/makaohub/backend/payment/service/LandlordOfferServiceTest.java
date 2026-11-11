package com.makaohub.backend.payment.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.payment.domain.PaymentIntent;
import com.makaohub.backend.payment.domain.PaymentStatus;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.domain.PaymentTargetKind;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.verification.service.RoleVerificationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LandlordOfferServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final RoleVerificationGuard verification = mock(RoleVerificationGuard.class);
    private final LandlordOfferService service = new LandlordOfferService(
            jdbc, listings, mock(UserRepository.class), verification);

    @Test
    void cataloguePricesAndLimitsAreExactMinorUnits() {
        assertEquals(49900, LandlordOffer.PRO_30D.amountMinor);
        assertEquals(5, LandlordOffer.PRO_30D.listingLimit);
        assertEquals(149900, LandlordOffer.PRO_MAX_30D.amountMinor);
        assertEquals(20, LandlordOffer.PRO_MAX_30D.listingLimit);
        assertEquals(29900, LandlordOffer.BOOST_7D.amountMinor);
        assertEquals(7, LandlordOffer.BOOST_7D.duration.toDays());
        assertEquals(49900, LandlordOffer.BOOST_14D.amountMinor);
        assertEquals(14, LandlordOffer.BOOST_14D.duration.toDays());
    }

    @Test
    void boostCannotChargeAnotherOwnersOrUnpublishedListing() {
        UUID owner = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        assertThrows(ResponseStatusException.class,
                () -> service.requireOffer(owner, "BOOST_7D", listing));
        verify(listings).findByIdAndLandlordId(listing, owner);
        verify(verification).requireVerified(owner, RoleName.LANDLORD);
        assertThrows(ResponseStatusException.class,
                () -> service.requireOffer(owner, "UNKNOWN", listing));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void onlyConfirmedMatchingOfferWritesAnIdempotentGrant() {
        PaymentIntent payment = mock(PaymentIntent.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        service.activate(payment);
        verify(jdbc, never()).update(anyString(), any(Object[].class));

        when(payment.getStatus()).thenReturn(PaymentStatus.SUCCEEDED);
        when(payment.getOfferCode()).thenReturn("BOOST_7D");
        when(payment.getPaymentType()).thenReturn(PaymentType.BOOST);
        when(payment.getAmountMinor()).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> service.activate(payment));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void confirmedBoostWritesOnlyTheServerPricedGrant() {
        UUID paymentId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        PaymentIntent payment = mock(PaymentIntent.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.SUCCEEDED);
        when(payment.getOfferCode()).thenReturn("BOOST_7D");
        when(payment.getPaymentType()).thenReturn(PaymentType.BOOST);
        when(payment.getAmountMinor()).thenReturn(29900L);
        when(payment.getCurrency()).thenReturn("KES");
        when(payment.getId()).thenReturn(paymentId);
        when(payment.getPayerUserId()).thenReturn(owner);
        when(payment.getListingId()).thenReturn(listing);
        Instant completed = Instant.parse("2026-11-02T09:00:00Z");
        when(payment.getCompletedAt()).thenReturn(completed);

        service.activate(payment);

        verify(jdbc).update(anyString(), eq(paymentId), eq(owner), eq(listing),
                eq("BOOST_7D"), eq(Timestamp.from(completed)),
                eq(Timestamp.from(completed.plus(LandlordOffer.BOOST_7D.duration))));
    }

    @Test
    void sublessorBoostRequiresVerifiedOwnerAndTheirPublishedListing() {
        UUID owner = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        assertThrows(ResponseStatusException.class,
                () -> service.requireSublessorOffer(owner, "SUB_BOOST_7D", listing));
        verify(verification).requireVerified(owner, RoleName.SUBLESSOR);
        verify(jdbc).queryForObject(anyString(), eq(Boolean.class), eq(listing), eq(owner));
        assertThrows(ResponseStatusException.class,
                () -> service.requireSublessorOffer(owner, "BOOST_7D", listing));
    }

    @Test
    void confirmedSublessorBoostUsesSeparateGrantAndMatchingTarget() {
        PaymentIntent payment = mock(PaymentIntent.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.SUCCEEDED);
        when(payment.getOfferCode()).thenReturn("SUB_BOOST_7D");
        when(payment.getPaymentType()).thenReturn(PaymentType.BOOST);
        when(payment.getAmountMinor()).thenReturn(29900L);
        when(payment.getCurrency()).thenReturn("KES");
        when(payment.getTargetKind()).thenReturn(PaymentTargetKind.LANDLORD_LISTING);
        assertThrows(IllegalStateException.class, () -> service.activate(payment));
        verify(jdbc, never()).update(anyString(), any(Object[].class));

        UUID paymentId = UUID.randomUUID(), owner = UUID.randomUUID(), listing = UUID.randomUUID();
        when(payment.getTargetKind()).thenReturn(PaymentTargetKind.SUBLESSOR_LISTING);
        when(payment.getId()).thenReturn(paymentId);
        when(payment.getPayerUserId()).thenReturn(owner);
        when(payment.getListingId()).thenReturn(listing);
        Instant completed = Instant.parse("2026-11-04T09:00:00Z");
        when(payment.getCompletedAt()).thenReturn(completed);
        service.activate(payment);
        verify(jdbc).update(anyString(), eq(paymentId), eq(owner), eq(listing),
                eq("SUB_BOOST_7D"), eq(Timestamp.from(completed)),
                eq(Timestamp.from(completed.plus(LandlordOffer.BOOST_7D.duration))));
    }

    @Test
    void sublessorCannotPublishPastStarterLimit() {
        UUID owner = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        LandlordOfferService service = new LandlordOfferService(jdbc, listings, users, verification);
        when(users.findByIdForUpdate(owner)).thenReturn(Optional.of(mock(com.makaohub.backend.auth.domain.User.class)));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("COUNT(*) FROM sublessor_listings"),
                eq(Long.class), eq(owner))).thenReturn(1L);
        assertThrows(ResponseStatusException.class, () -> service.requireSublessorPublicationSlot(owner));
        verify(users).findByIdForUpdate(owner);
    }
}
