package com.makaohub.backend.property.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingMedia;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.repository.ListingLocationRepository;
import com.makaohub.backend.property.repository.ListingMediaRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LandlordDraftDeletionTest {

    @Test
    void ownerCanDeleteOnlyTheirDraftAndItsPrivateMedia() {
        Fixture fixture = new Fixture();
        UUID actor = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        Listing listing = mock(Listing.class);
        ListingMedia first = mock(ListingMedia.class);
        ListingMedia second = mock(ListingMedia.class);

        fixture.activeLandlord(actor);
        when(fixture.listings.findByIdAndLandlordId(listingId, actor))
                .thenReturn(Optional.of(listing));
        when(listing.getStatus()).thenReturn(ListingStatus.DRAFT);
        when(fixture.media.findAllByListingIdOrderBySortOrderAsc(listingId))
                .thenReturn(List.of(first, second));
        when(first.getObjectPath()).thenReturn("owner/listing/first.jpg");
        when(second.getObjectPath()).thenReturn("owner/listing/second.jpg");

        fixture.service.deleteDraft(actor, listingId);

        verify(fixture.storage).deleteListingMedia("owner/listing/first.jpg");
        verify(fixture.storage).deleteListingMedia("owner/listing/second.jpg");
        verify(fixture.listings).delete(listing);
    }

    @Test
    void ownershipLookupDoesNotRevealOrDeleteAnotherLandlordsDraft() {
        Fixture fixture = new Fixture();
        UUID actor = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        fixture.activeLandlord(actor);
        when(fixture.listings.findByIdAndLandlordId(listingId, actor))
                .thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.deleteDraft(actor, listingId)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(fixture.storage);
        verify(fixture.listings, never()).delete(any());
    }

    @Test
    void publishedListingCannotBeDeletedThroughTheDraftEndpoint() {
        Fixture fixture = new Fixture();
        UUID actor = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        Listing listing = mock(Listing.class);
        fixture.activeLandlord(actor);
        when(fixture.listings.findByIdAndLandlordId(listingId, actor))
                .thenReturn(Optional.of(listing));
        when(listing.getStatus()).thenReturn(ListingStatus.PUBLISHED);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.deleteDraft(actor, listingId)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(fixture.storage);
        verify(fixture.listings, never()).delete(any());
    }

    @Test
    void storageFailureLeavesTheDatabaseDraftInPlaceForAnExplicitRetry() {
        Fixture fixture = new Fixture();
        UUID actor = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        Listing listing = mock(Listing.class);
        ListingMedia media = mock(ListingMedia.class);
        fixture.activeLandlord(actor);
        when(fixture.listings.findByIdAndLandlordId(listingId, actor))
                .thenReturn(Optional.of(listing));
        when(listing.getStatus()).thenReturn(ListingStatus.DRAFT);
        when(fixture.media.findAllByListingIdOrderBySortOrderAsc(listingId))
                .thenReturn(List.of(media));
        when(media.getObjectPath()).thenReturn("owner/listing/photo.jpg");
        doThrow(new IllegalStateException("storage unavailable"))
                .when(fixture.storage)
                .deleteListingMedia("owner/listing/photo.jpg");

        assertThrows(
                IllegalStateException.class,
                () -> fixture.service.deleteDraft(actor, listingId)
        );

        verify(fixture.listings, never()).delete(any());
    }

    private static final class Fixture {
        private final UserRepository users = mock(UserRepository.class);
        private final UserRoleRepository roles = mock(UserRoleRepository.class);
        private final ListingRepository listings = mock(ListingRepository.class);
        private final ListingLocationRepository locations = mock(ListingLocationRepository.class);
        private final ListingMediaRepository media = mock(ListingMediaRepository.class);
        private final RoleVerificationRepository verifications = mock(RoleVerificationRepository.class);
        private final SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
        private final LandlordListingService service = new LandlordListingService(
                users,
                roles,
                listings,
                locations,
                media,
                verifications,
                storage,
                mock(com.makaohub.backend.payment.service.LandlordOfferService.class)
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
