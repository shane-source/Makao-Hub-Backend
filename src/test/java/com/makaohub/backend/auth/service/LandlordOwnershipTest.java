package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.property.repository.ListingLocationRepository;
import com.makaohub.backend.property.service.LandlordListingLocationService;
import com.makaohub.backend.property.service.ListingLocationProtectionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LandlordOwnershipTest {
    @Test
    void landlordCannotReadAnotherLandlordsExactLocation() {
        UUID caller = UUID.randomUUID();
        UUID otherLandlordsListing = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        UserRoleRepository roles = mock(UserRoleRepository.class);
        ListingRepository listings = mock(ListingRepository.class);
        ListingLocationRepository locations = mock(ListingLocationRepository.class);
        ListingLocationProtectionService encryption = mock(ListingLocationProtectionService.class);
        User user = mock(User.class);
        when(users.findById(caller)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(roles.existsByUser_IdAndRole_Name(caller, RoleName.LANDLORD)).thenReturn(true);
        when(listings.findByIdAndLandlordId(otherLandlordsListing, caller)).thenReturn(Optional.empty());
        var service = new LandlordListingLocationService(users, roles, listings, locations, encryption);

        var error = assertThrows(ResponseStatusException.class,
                () -> service.getLocation(caller, otherLandlordsListing));

        assertEquals(404, error.getStatusCode().value());
        verify(listings).findByIdAndLandlordId(otherLandlordsListing, caller);
        verifyNoInteractions(locations, encryption);
    }
}
