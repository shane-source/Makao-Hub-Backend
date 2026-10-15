package com.makaohub.backend.property.api;
import com.makaohub.backend.property.service.RenterHomeService;
import com.makaohub.backend.property.service.PublicListingService;
import com.makaohub.backend.property.service.PublicSublessorListingService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/api/v1/renter/homes")
public class RenterHomeController {
    private final RenterHomeService homes;
    private final PublicListingService landlord;
    private final PublicSublessorListingService sublessor;
    public RenterHomeController(RenterHomeService homes, PublicListingService landlord, PublicSublessorListingService sublessor) {
        this.homes=homes; this.landlord=landlord; this.sublessor=sublessor;
    }
    @GetMapping public RenterHomeService.HomePage search(@AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) String area,@RequestParam(required=false) Long minRentKes,
        @RequestParam(required=false) Long maxRentKes,@RequestParam(required=false) String source,
        @RequestParam(required=false) Integer minBedrooms,@RequestParam(required=false) Boolean furnished,
        @RequestParam(defaultValue="false") boolean saved) {
        return homes.search(actor(jwt),page,size,area,minRentKes,maxRentKes,source,minBedrooms,furnished,saved);
    }
    @GetMapping("/{source}/{id}") public RenterHomeService.Home one(@AuthenticationPrincipal Jwt jwt,@PathVariable String source,@PathVariable UUID id) {
        return homes.one(actor(jwt),source,id);
    }
    @GetMapping("/{source}/{id}/photos") public List<PublicListingMediaResponse> photos(@AuthenticationPrincipal Jwt jwt,@PathVariable String source,@PathVariable UUID id) {
        homes.one(actor(jwt),source,id); // Recheck current publication/verified owner before minting capabilities.
        return source.equals("LANDLORD")?landlord.findPublishedMedia(id):sublessor.findPublishedMedia(id);
    }
    @PutMapping("/{source}/{id}/save") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@AuthenticationPrincipal Jwt jwt,@PathVariable String source,@PathVariable UUID id) { homes.save(actor(jwt),source,id); }
    @DeleteMapping("/{source}/{id}/save") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt,@PathVariable String source,@PathVariable UUID id) { homes.remove(actor(jwt),source,id); }
    private UUID actor(Jwt jwt) {
        try { if(jwt==null) throw new IllegalArgumentException(); return UUID.fromString(jwt.getSubject()); }
        catch(IllegalArgumentException|NullPointerException failure) { throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,"Valid authentication required."); }
    }
}
