package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingMedia;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.repository.ListingMediaRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LandlordMediaDeletionTest {

    @Test
    void ownerCanDeleteMediaFromTheirDraft() {
        Fixture fixture = new Fixture();
        UUID actor = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Listing listing = mock(Listing.class);
        ListingMedia media = mock(ListingMedia.class);
        fixture.activeLandlord(actor);
        when(fixture.listings.findByIdAndLandlordId(listingId, actor))
                .thenReturn(Optional.of(listing));
        when(listing.getStatus()).thenReturn(ListingStatus.DRAFT);
        when(fixture.media.findById(mediaId)).thenReturn(Optional.of(media));
        when(media.getListingId()).thenReturn(listingId);
        when(media.getObjectPath()).thenReturn("owner/listing/photo.jpg");

        fixture.service.deleteMedia(actor, listingId, mediaId);

        verify(fixture.storage).deleteListingMedia("owner/listing/photo.jpg");
        verify(fixture.media).delete(media);
    }

    @Test
    void mediaFromAnotherListingIsHiddenAndNotDeleted() {
        Fixture fixture = new Fixture();
        UUID actor = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Listing listing = mock(Listing.class);
        ListingMedia media = mock(ListingMedia.class);
        fixture.activeLandlord(actor);
        when(fixture.listings.findByIdAndLandlordId(listingId, actor))
                .thenReturn(Optional.of(listing));
        when(listing.getStatus()).thenReturn(ListingStatus.DRAFT);
        when(fixture.media.findById(mediaId)).thenReturn(Optional.of(media));
        when(media.getListingId()).thenReturn(UUID.randomUUID());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.deleteMedia(actor, listingId, mediaId)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(fixture.storage);
        verify(fixture.media, never()).delete(any());
    }

    private static final class Fixture {
        private final UserRepository users = mock(UserRepository.class);
        private final UserRoleRepository roles = mock(UserRoleRepository.class);
        private final ListingRepository listings = mock(ListingRepository.class);
        private final ListingMediaRepository media = mock(ListingMediaRepository.class);
        private final SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
        private final LandlordListingMediaService service = new LandlordListingMediaService(
                users,
                roles,
                listings,
                media,
                storage
        );

        private void activeLandlord(UUID actor) {
            User user = mock(User.class);
            when(users.findById(actor)).thenReturn(Optional.of(user));
            when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
            when(roles.existsByUser_IdAndRole_Name(actor, RoleName.LANDLORD))
                    .thenReturn(true);
        }
    }
}
