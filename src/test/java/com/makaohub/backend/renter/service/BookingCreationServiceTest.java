package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.PropertyType;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.domain.RentalApplication;
import com.makaohub.backend.renter.repository.BookingRepository;
import com.makaohub.backend.renter.repository.RentalApplicationRepository;
import com.makaohub.backend.renter.repository.SublessorRentalApplicationRepository;
import com.makaohub.backend.verification.service.RoleVerificationGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BookingCreationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private ListingRepository landlordListingRepository;
    @Mock private SublessorListingRepository sublessorListingRepository;
    @Mock private RentalApplicationRepository rentalApplicationRepository;
    @Mock private SublessorRentalApplicationRepository sublessorApplicationRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingDepositPricingService depositPricingService;
    @Mock private BookingCommissionService commissionService;
    @Mock private RoleVerificationGuard verificationGuard;

    @Test
    void snapshotsRentAndDepositOnlyForApprovedApplication() {
        UUID renterId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        User user = org.mockito.Mockito.mock(User.class);
        RentalApplication application = new RentalApplication(renterId, listingId);
        setId(application, applicationId);
        application.markUnderReview();
        application.approve();

        Listing listing = new Listing(
                UUID.randomUUID(), "Home", "Description", PropertyType.BEDSITTER,
                12_000, (short) 1, (short) 1, false, LocalDate.now()
        );
        setId(listing, listingId);
        listing.publish();

        when(userRepository.findById(renterId)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(userRoleRepository.existsByUser_IdAndRole_Name(any(), any()))
                .thenReturn(true);
        when(rentalApplicationRepository.findByIdAndRenterId(applicationId, renterId))
                .thenReturn(Optional.of(application));
        when(landlordListingRepository.findByIdAndStatus(listingId, ListingStatus.PUBLISHED))
                .thenReturn(Optional.of(listing));
        when(bookingRepository.findByListingKindAndSourceApplicationId(any(), any()))
                .thenReturn(Optional.empty());
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(depositPricingService.depositAmountMinor()).thenReturn(5_000L);
        when(commissionService.commissionAmountMinor(1_200_000L, 5_000L))
                .thenReturn(60_250L);

        Booking booking = service().createForLandlordApplication(
                renterId, applicationId
        );

        assertEquals(1_200_000, booking.getRentAmountMinor());
        assertEquals(5_000, booking.getDepositAmountMinor());
        assertEquals(60_250, booking.getCommissionAmountMinor());
        assertEquals(1_265_250, booking.getTotalAmountMinor());
        verify(verificationGuard).requireVerified(
                renterId, com.makaohub.backend.auth.domain.RoleName.RENTER
        );
    }

    @Test
    void rejectsUnapprovedApplication() {
        UUID renterId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        User user = org.mockito.Mockito.mock(User.class);
        RentalApplication application = new RentalApplication(
                renterId, UUID.randomUUID()
        );

        when(userRepository.findById(renterId)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(userRoleRepository.existsByUser_IdAndRole_Name(any(), any()))
                .thenReturn(true);
        when(rentalApplicationRepository.findByIdAndRenterId(applicationId, renterId))
                .thenReturn(Optional.of(application));

        assertThrows(
                ResponseStatusException.class,
                () -> service().createForLandlordApplication(
                    renterId, applicationId
                )
        );
    }

    private BookingCreationService service() {
        return new BookingCreationService(
                userRepository,
                userRoleRepository,
                landlordListingRepository,
                sublessorListingRepository,
                rentalApplicationRepository,
                sublessorApplicationRepository,
                bookingRepository,
                depositPricingService,
                commissionService,
                verificationGuard
        );
    }

    private static void setId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
