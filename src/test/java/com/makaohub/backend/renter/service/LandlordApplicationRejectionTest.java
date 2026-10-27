package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.renter.domain.RentalApplication;
import com.makaohub.backend.renter.repository.RentalApplicationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LandlordApplicationRejectionTest {

    @Test
    void ownerMustSaveASpecificReasonWhenRejectingTheirApplication() {
        Fixture fixture = new Fixture();
        UUID landlordId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        RentalApplication application = new RentalApplication(UUID.randomUUID(), listingId);
        application.markUnderReview();
        fixture.activeLandlord(landlordId);
        when(fixture.applications.findById(applicationId)).thenReturn(Optional.of(application));
        when(fixture.listings.findByIdAndLandlordId(listingId, landlordId))
                .thenReturn(Optional.of(mock(Listing.class)));

        var response = fixture.service.reject(
                landlordId,
                applicationId,
                "Move-in date is unavailable."
        );

        assertEquals("Move-in date is unavailable.", response.rejectionReason());
        assertEquals("REJECTED", response.status().name());
    }

    @Test
    void blankReasonIsRejectedWithoutChangingTheApplicationState() {
        Fixture fixture = new Fixture();
        UUID landlordId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        RentalApplication application = new RentalApplication(UUID.randomUUID(), listingId);
        application.markUnderReview();
        fixture.activeLandlord(landlordId);
        when(fixture.applications.findById(applicationId)).thenReturn(Optional.of(application));
        when(fixture.listings.findByIdAndLandlordId(listingId, landlordId))
                .thenReturn(Optional.of(mock(Listing.class)));

        assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.reject(landlordId, applicationId, "   ")
        );
        assertEquals("UNDER_REVIEW", application.getStatus().name());
    }

    private static final class Fixture {
        private final UserRepository users = mock(UserRepository.class);
        private final UserRoleRepository roles = mock(UserRoleRepository.class);
        private final ListingRepository listings = mock(ListingRepository.class);
        private final RentalApplicationRepository applications = mock(RentalApplicationRepository.class);
        private final LandlordApplicationService service = new LandlordApplicationService(
                users,
                roles,
                listings,
                applications
        );

        private void activeLandlord(UUID landlordId) {
            User user = mock(User.class);
            when(users.findById(landlordId)).thenReturn(Optional.of(user));
            when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
            when(roles.existsByUser_IdAndRole_Name(landlordId, RoleName.LANDLORD))
                    .thenReturn(true);
        }
    }
}
