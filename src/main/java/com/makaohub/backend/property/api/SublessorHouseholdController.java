package com.makaohub.backend.property.api;

import com.makaohub.backend.property.service.SublessorHouseholdService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sublessor/listings/{listingId}/household")
public class SublessorHouseholdController {
    public enum Amenity { WATER, WIFI, SECURITY, PARKING, PETS }
    public record Household(@PositiveOrZero Long depositKes, @Size(max=600) String currentHousehold,
        @Min(1) @Max(20) Integer totalOccupants, @Min(1) @Max(10) Integer peoplePerRoom,
        @Size(max=600) String lifestyle, @NotNull @Size(max=5) Set<Amenity> amenities) {}
    private final SublessorHouseholdService service;
    public SublessorHouseholdController(SublessorHouseholdService service) { this.service = service; }
    @GetMapping public Household get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID listingId) {
        return service.get(UUID.fromString(jwt.getSubject()), listingId);
    }
    @PutMapping public Household save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID listingId, @Valid @RequestBody Household body) {
        return service.save(UUID.fromString(jwt.getSubject()), listingId, body);
    }
}
