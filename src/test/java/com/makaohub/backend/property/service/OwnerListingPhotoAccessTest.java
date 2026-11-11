package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.repository.ListingMediaRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.property.repository.SublessorListingMediaRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class OwnerListingPhotoAccessTest {

    @Test
    void landlordCannotReadAnotherOwnersPhotoUrls() {
        UUID actor = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        UserRoleRepository roles = mock(UserRoleRepository.class);
        ListingRepository listings = mock(ListingRepository.class);
        ListingMediaRepository media = mock(ListingMediaRepository.class);
        SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
        activeRole(users, roles, actor, RoleName.LANDLORD);
        when(listings.findByIdAndLandlordId(listingId, actor)).thenReturn(Optional.empty());
        LandlordListingMediaService service = new LandlordListingMediaService(users, roles, listings, media, storage);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.findPhotosForOwner(actor, listingId));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(media, storage);
    }

    @Test
    void sublessorCannotReadAnotherOwnersPhotoUrls() {
        UUID actor = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        UserRoleRepository roles = mock(UserRoleRepository.class);
        SublessorListingRepository listings = mock(SublessorListingRepository.class);
        SublessorListingMediaRepository media = mock(SublessorListingMediaRepository.class);
        SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
        activeRole(users, roles, actor, RoleName.SUBLESSOR);
        when(listings.findByIdAndSublessorId(listingId, actor)).thenReturn(Optional.empty());
        SublessorListingMediaService service = new SublessorListingMediaService(users, roles, listings, media, storage);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.findPhotosForOwner(actor, listingId));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(media, storage);
    }

    private static void activeRole(
            UserRepository users,
            UserRoleRepository roles,
            UUID actor,
            RoleName role
    ) {
        User user = mock(User.class);
        when(users.findById(actor)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(roles.existsByUser_IdAndRole_Name(actor, role)).thenReturn(true);
    }
}
