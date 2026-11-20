package com.makaohub.backend.admin;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AmenityControllerTest {
    @Test void ownerCanChangeAmenitiesOnlyForUnsubmittedDraft() {
        UUID owner=UUID.randomUUID(),listing=UUID.randomUUID(),amenity=UUID.randomUUID();
        var jwt=mock(Jwt.class); when(jwt.getSubject()).thenReturn(owner.toString());
        when(jwt.getClaimAsStringList("roles")).thenReturn(List.of("LANDLORD"));
        var status=new java.util.concurrent.atomic.AtomicReference<>("DRAFT");
        AdminStore db=mock(AdminStore.class, call -> switch(call.getMethod().getName()) {
            case "one" -> ((String)call.getArgument(0)).startsWith("SELECT status")
                    ? new HashMap<>(Map.of("status",status.get())) : Map.of("id",listing);
            case "exists" -> true;
            case "rows" -> List.of();
            case "update" -> 1;
            default -> null;
        });
        var controller=new AmenityController(db,mock(StaffAccessService.class));
        assertDoesNotThrow(()->controller.save(jwt,"landlord",listing,new AmenityController.Selection(Set.of(amenity))));
        status.set("PUBLISHED");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.save(jwt,"landlord",listing,new AmenityController.Selection(Set.of(amenity)))).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.save(jwt,"sublessor",listing,new AmenityController.Selection(Set.of(amenity)))).getStatusCode().value());
    }
}
