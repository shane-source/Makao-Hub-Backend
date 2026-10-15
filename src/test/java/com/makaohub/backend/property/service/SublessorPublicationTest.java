package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.domain.ListingMediaType;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.domain.SublessorListingLocation;
import com.makaohub.backend.property.repository.SublessorListingLocationRepository;
import com.makaohub.backend.property.repository.SublessorListingMediaRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.verification.domain.RoleVerification;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SublessorPublicationTest {
    @Test
    void videoOnlyCannotSatisfyTheRequiredPhoto() {
        UUID actor = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var users = mock(UserRepository.class);
        var roles = mock(UserRoleRepository.class);
        var listings = mock(SublessorListingRepository.class);
        var locations = mock(SublessorListingLocationRepository.class);
        var media = mock(SublessorListingMediaRepository.class);
        var verifications = mock(RoleVerificationRepository.class);
        var user = mock(User.class);
        var listing = mock(SublessorListing.class);
        var verification = mock(RoleVerification.class);
        when(users.findById(actor)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(roles.existsByUser_IdAndRole_Name(actor, RoleName.SUBLESSOR)).thenReturn(true);
        when(listings.findByIdAndSublessorId(listingId, actor)).thenReturn(Optional.of(listing));
        when(verifications.findByUserIdAndRole(actor, RoleName.SUBLESSOR)).thenReturn(Optional.of(verification));
        when(verification.getStatus()).thenReturn(VerificationStatus.VERIFIED);
        when(locations.findBySublessorListingId(listingId)).thenReturn(Optional.of(mock(SublessorListingLocation.class)));
        when(media.existsBySublessorListingIdAndConfirmedAtIsNotNull(listingId)).thenReturn(true);
        when(media.existsBySublessorListingIdAndMediaTypeAndConfirmedAtIsNotNull(listingId, ListingMediaType.IMAGE)).thenReturn(false);

        var service = new SublessorListingService(users, roles, listings, locations, media, verifications);
        var error = assertThrows(ResponseStatusException.class, () -> service.publish(actor, listingId));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(listing, never()).publish();
        verify(listings, never()).save(any());
    }
}
