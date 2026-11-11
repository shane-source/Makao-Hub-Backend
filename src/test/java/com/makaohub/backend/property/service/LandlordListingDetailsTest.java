package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.api.CreateListingDraftRequest;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.PropertyType;
import com.makaohub.backend.property.repository.ListingLocationRepository;
import com.makaohub.backend.property.repository.ListingMediaRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LandlordListingDetailsTest {

    @Test
    void draftPersistsTheDepositAndAmenitiesShownInTheLandlordForm() {
        UserRepository users = mock(UserRepository.class);
        UserRoleRepository roles = mock(UserRoleRepository.class);
        ListingRepository listings = mock(ListingRepository.class);
        User user = mock(User.class);
        UUID landlordId = UUID.randomUUID();
        when(users.findById(landlordId)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(roles.existsByUser_IdAndRole_Name(landlordId, RoleName.LANDLORD)).thenReturn(true);
        when(listings.save(any(Listing.class))).thenAnswer(call -> call.getArgument(0));
        LandlordListingService service = new LandlordListingService(
                users,
                roles,
                listings,
                mock(ListingLocationRepository.class),
                mock(ListingMediaRepository.class),
                mock(RoleVerificationRepository.class),
                mock(SupabaseStorageClient.class),
                mock(com.makaohub.backend.payment.service.LandlordOfferService.class)
        );

        var response = service.createDraft(
                landlordId,
                new CreateListingDraftRequest(
                        "Sunlit apartment",
                        "A quiet whole unit",
                        PropertyType.APARTMENT,
                        45_000,
                        (short) 2,
                        (short) 2,
                        false,
                        LocalDate.of(2026, 11, 1),
                        45_000L,
                        true,
                        true,
                        true,
                        true,
                        false
                )
        );

        assertEquals(45_000L, response.depositKes());
        assertTrue(response.waterAvailable());
        assertTrue(response.securityAvailable());
        assertTrue(response.parkingAvailable());
        assertTrue(response.balcony());
    }
}
