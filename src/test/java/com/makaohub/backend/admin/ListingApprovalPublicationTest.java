package com.makaohub.backend.admin;

import com.makaohub.backend.messaging.service.ChatContactMaskingService;
import com.makaohub.backend.payment.service.LandlordOfferService;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListingApprovalPublicationTest {
    @Test void publishesLandlordAndSublessorOnlyWhenTheirPlanHasAFreeSlot() { approve(0L,"PUBLISHED",true); }
    @Test void approvesButDoesNotPublishWhenThePlanLimitIsReached() { approve(1L,"DRAFT",false); }

    private void approve(long alreadyPublished,String expectedStatus,boolean expectedPublished) {
        for (String kind : new String[]{"LANDLORD", "SUBLESSOR"}) {
            UUID owner=UUID.randomUUID(), listing=UUID.randomUUID(), actor=UUID.randomUUID();
            Instant version=Instant.parse("2026-01-01T00:00:00Z");
            String ownerColumn=kind.equals("LANDLORD") ? "landlord_id" : "sublessor_id";
            AdminStore db=mock(AdminStore.class, call -> {
                return switch (call.getMethod().getName()) {
                    case "command" -> ((Supplier<?>)call.getArgument(3)).get();
                    case "one" -> {
                        String sql=call.getArgument(0);
                        if (sql.startsWith("SELECT COUNT(*)")) yield Map.of("n",alreadyPublished);
                        if (sql.startsWith("SELECT "+ownerColumn)) yield Map.of(ownerColumn,owner);
                        if (sql.startsWith("SELECT *")) yield Map.of("status","DRAFT","moderation_status","PENDING","moderation_submitted_at",version,"updated_at",version);
                        yield Map.of("id",owner);
                    }
                    case "exists" -> true;
                    case "update" -> 1;
                    default -> null;
                };
            });
            StaffAccessService access=mock(StaffAccessService.class);
            Jwt jwt=mock(Jwt.class);
            when(access.require(jwt,StaffRole.Permission.LISTING_MODERATION)).thenReturn(new StaffAccessService.Staff(actor,StaffRole.STAFF_LEAD,true));
            LandlordOfferService offers=mock(LandlordOfferService.class);
            when(offers.activeListingLimit(owner)).thenReturn(1);
            when(offers.activeSublessorListingLimit(owner)).thenReturn(1);
            AdminWorkflowService work=new AdminWorkflowService(db,access,mock(SupabaseStorageClient.class),mock(ChatContactMaskingService.class),offers);
            var result=work.decideListing(jwt,UUID.randomUUID(),kind,listing,new AdminRequests.Decision(true,version,"Listing reviewed"));
            assertEquals(expectedStatus,result.get("status"));
            assertEquals(expectedPublished,result.get("published"));
            assertEquals("APPROVED",result.get("moderationStatus"));
            verify(offers,never()).requirePublicationSlot(any());
            verify(offers,never()).requireSublessorPublicationSlot(any());
        }
    }
}
