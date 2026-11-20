package com.makaohub.backend.admin;

import com.makaohub.backend.messaging.service.ChatContactMaskingService;
import com.makaohub.backend.payment.service.LandlordOfferService;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListingRevisionTest {
    private final AdminStore db = mock(AdminStore.class);
    private final AdminWorkflowService service = new AdminWorkflowService(db, mock(StaffAccessService.class),
            mock(SupabaseStorageClient.class), mock(ChatContactMaskingService.class), mock(LandlordOfferService.class));

    @Test void publishedListingReturnsToAnUnsubmittedDraftForEachOwnerRole() {
        for (String role : List.of("LANDLORD", "SUBLESSOR")) {
            UUID owner = UUID.randomUUID(), listing = UUID.randomUUID();
            Jwt token = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString())
                    .claim("roles", List.of(role)).build();
            when(db.one(anyString(), eq(listing), eq(owner))).thenReturn(Map.of("id", listing, "status", "PUBLISHED", "moderation_status", "APPROVED"));
            assertEquals("DRAFT", service.reviseListing(token, role, listing).get("status"));
            verify(db).one(contains("FOR UPDATE"), eq(listing), eq(owner));
            verify(db).update(contains("moderation_submitted_at=NULL"), eq(listing));
        }
    }

    @Test void wrongRoleAndMissingOwnedListingCannotBeRevised() {
        UUID owner = UUID.randomUUID(), listing = UUID.randomUUID();
        Jwt renter = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString())
                .claim("roles", List.of("RENTER")).build();
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.reviseListing(renter, "LANDLORD", listing)).getStatusCode());
        verifyNoInteractions(db);
        Jwt landlord = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString())
                .claim("roles", List.of("LANDLORD")).build();
        when(db.one(anyString(), eq(listing), eq(owner))).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.reviseListing(landlord, "LANDLORD", listing)).getStatusCode());
        verify(db, never()).update(anyString(), any());
    }
}
