package com.makaohub.backend.roommate.api;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.roommate.service.RoommatePreferencesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
public class RoommatePreferencesController {
    public record Preferences(
        @NotNull @Pattern(regexp="QUIET|SOCIAL|BALANCED") String quietSocial,
        @NotNull @Pattern(regexp="EARLY|NIGHT|FLEXIBLE") String sleepSchedule,
        @NotNull @Pattern(regexp="DAILY|WEEKLY") String cleanliness,
        @NotNull @Pattern(regexp="REGULAR|OCCASIONAL|NONE") String cooking,
        @NotNull @Pattern(regexp="ASK_FIRST|OCCASIONAL|FREQUENT") String guests,
        @NotNull @Pattern(regexp="NO_INDOOR|OUTSIDE_ONLY|SMOKER") String smoking,
        @NotNull @Pattern(regexp="NONE|PETS_OK|HAS_PETS") String pets,
        @Positive long budgetMinKes, @Positive long budgetMaxKes, boolean discoveryOptIn,
        @Size(max=150) String areaLabel, @Size(max=600) String about,
        @Size(max=180) String employment, LocalDate moveInDate) {
        @AssertTrue public boolean isBudgetRangeValid() { return budgetMaxKes >= budgetMinKes; }
    }
    public record PublicProfile(UUID userId, String displayName, Preferences preferences) {}
    public record DiscoveryPage(List<PublicProfile> items, int page, int size, long totalElements, boolean hasNext) {}
    private final RoommatePreferencesService service;
    public RoommatePreferencesController(RoommatePreferencesService service) { this.service = service; }
    @GetMapping("/api/v1/sublessor/roommate-preferences") public Preferences sublessorGet(@AuthenticationPrincipal Jwt jwt) { return service.get(id(jwt), RoleName.SUBLESSOR); }
    @PutMapping("/api/v1/sublessor/roommate-preferences") public Preferences sublessorSave(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Preferences body) { return service.save(id(jwt), RoleName.SUBLESSOR, body); }
    @GetMapping("/api/v1/renter/roommate-preferences") public Preferences renterGet(@AuthenticationPrincipal Jwt jwt) { return service.get(id(jwt), RoleName.RENTER); }
    @PutMapping("/api/v1/renter/roommate-preferences") public Preferences renterSave(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Preferences body) { return service.save(id(jwt), RoleName.RENTER, body); }
    @GetMapping("/api/v1/sublessor/roommate-discovery") public DiscoveryPage discover(@AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) { return service.discover(id(jwt), page, size); }
    @GetMapping("/api/v1/sublessor/roommate-discovery/{renterId}") public PublicProfile profile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID renterId) { return service.profile(id(jwt), renterId); }
    private UUID id(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
