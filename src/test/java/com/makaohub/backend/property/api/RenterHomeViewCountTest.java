package com.makaohub.backend.property.api;

import com.makaohub.backend.property.service.PublicListingService;
import com.makaohub.backend.property.service.PublicSublessorListingService;
import com.makaohub.backend.property.service.RenterHomeService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;
import static org.mockito.Mockito.*;

class RenterHomeViewCountTest {
    @Test void photoLookupRechecksAvailabilityWithoutCountingAView() {
        var homes = mock(RenterHomeService.class);
        var landlord = mock(PublicListingService.class);
        var sublessor = mock(PublicSublessorListingService.class);
        var actor = UUID.randomUUID();
        var listing = UUID.randomUUID();
        var home = mock(RenterHomeService.Home.class);
        when(home.bookedByYou()).thenReturn(false);
        when(homes.available(actor, "LANDLORD", listing)).thenReturn(home);
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject(actor.toString()).build();
        var controller = new RenterHomeController(homes, landlord, sublessor);
        controller.photos(jwt, "LANDLORD", listing);
        verify(homes).available(actor, "LANDLORD", listing);
        verify(homes, never()).one(any(), any(), any());
        verify(landlord).findPublishedMedia(listing);
        verify(landlord, never()).findMediaForBookedRenter(any());
    }

    @Test void renterWhoBookedTheHomeGetsThePhotosEvenAfterItIsArchived() {
        var homes = mock(RenterHomeService.class);
        var landlord = mock(PublicListingService.class);
        var sublessor = mock(PublicSublessorListingService.class);
        var actor = UUID.randomUUID();
        var listing = UUID.randomUUID();
        var home = mock(RenterHomeService.Home.class);
        when(home.bookedByYou()).thenReturn(true);
        when(homes.available(actor, "LANDLORD", listing)).thenReturn(home);
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject(actor.toString()).build();
        var controller = new RenterHomeController(homes, landlord, sublessor);
        controller.photos(jwt, "LANDLORD", listing);
        verify(landlord).findMediaForBookedRenter(listing);
        verify(landlord, never()).findPublishedMedia(any());
    }
}
