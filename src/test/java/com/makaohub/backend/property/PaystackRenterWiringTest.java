package com.makaohub.backend.property;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.auth.service.PhoneProtectionService;
import com.makaohub.backend.payment.domain.PaymentType;
import com.makaohub.backend.payment.entitlement.service.ListingUnlockEntitlementService;
import com.makaohub.backend.property.api.RenterListingController;
import com.makaohub.backend.property.api.RenterListingLocationController;
import com.makaohub.backend.property.repository.ListingLocationRepository;
import com.makaohub.backend.property.repository.ListingMediaRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.property.repository.SublessorListingLocationRepository;
import com.makaohub.backend.property.repository.SublessorListingMediaRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.property.service.ListingLocationProtectionService;
import com.makaohub.backend.property.service.PublicListingService;
import com.makaohub.backend.property.service.RenterHomeService;
import com.makaohub.backend.property.service.RenterListingLocationAccessService;
import com.makaohub.backend.property.service.RenterListingPhoneAccessService;
import com.makaohub.backend.property.service.RenterListingVideoAccessService;
import com.makaohub.backend.renter.api.DarajaRenterBookingController;
import com.makaohub.backend.renter.service.BookingCreationService;
import com.makaohub.backend.renter.service.BookingPaymentService;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises Paystack-profile renter wiring without a database or provider connection. */
class PaystackRenterWiringTest {

    @Test
    void paystackProfileProvidesBookingAndPaidAccessWithoutBypassingEntitlements() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("paystack");
            context.register(RenterListingController.class,
                    RenterListingLocationController.class,
                    RenterListingVideoAccessService.class,
                    RenterListingPhoneAccessService.class,
                    RenterListingLocationAccessService.class,
                    DarajaRenterBookingController.class);
            for (Class<?> type : List.of(UserRepository.class, UserRoleRepository.class,
                    ListingRepository.class, SublessorListingRepository.class,
                    ListingMediaRepository.class, SublessorListingMediaRepository.class,
                    ListingLocationRepository.class, SublessorListingLocationRepository.class,
                    ListingUnlockEntitlementService.class, SupabaseStorageClient.class,
                    PhoneProtectionService.class, ListingLocationProtectionService.class,
                    PublicListingService.class, RenterHomeService.class,
                    BookingCreationService.class, BookingPaymentService.class)) {
                context.getBeanFactory().registerSingleton(type.getSimpleName() + "Mock", mock(type));
            }
            context.refresh();

            assertNotNull(context.getBean(RenterListingController.class));
            assertNotNull(context.getBean(RenterListingLocationController.class));
            assertNotNull(context.getBean(DarajaRenterBookingController.class));

            UUID renterId = UUID.randomUUID();
            UUID listingId = UUID.randomUUID();
            User renter = mock(User.class);
            when(renter.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
            when(context.getBean(UserRepository.class).findById(renterId))
                    .thenReturn(Optional.of(renter));

            assertForbidden(() -> context.getBean(RenterListingVideoAccessService.class)
                    .getVideos(renterId, listingId));
            assertForbidden(() -> context.getBean(RenterListingPhoneAccessService.class)
                    .getOwnerPhone(renterId, listingId));
            assertForbidden(() -> context.getBean(RenterListingLocationAccessService.class)
                    .getExactLocation(renterId, listingId));
            ListingUnlockEntitlementService entitlements =
                    context.getBean(ListingUnlockEntitlementService.class);
            verifyNoInteractions(entitlements);

            when(context.getBean(UserRoleRepository.class)
                    .existsByUser_IdAndRole_Name(renterId, RoleName.RENTER)).thenReturn(true);

            assertForbidden(() -> context.getBean(RenterListingVideoAccessService.class)
                    .getVideos(renterId, listingId));
            assertForbidden(() -> context.getBean(RenterListingPhoneAccessService.class)
                    .getOwnerPhone(renterId, listingId));
            assertForbidden(() -> context.getBean(RenterListingLocationAccessService.class)
                    .getExactLocation(renterId, listingId));

            verify(entitlements).hasActiveUnlock(renterId, listingId, PaymentType.VIDEO_UNLOCK);
            verify(entitlements).hasActiveUnlock(renterId, listingId, PaymentType.PHONE_UNLOCK);
            verify(entitlements).hasActiveUnlock(renterId, listingId, PaymentType.EXACT_LOCATION_UNLOCK);
        }
    }

    private static void assertForbidden(Runnable request) {
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, request::run).getStatusCode());
    }
}
