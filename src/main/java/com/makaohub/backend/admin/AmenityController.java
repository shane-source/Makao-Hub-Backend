package com.makaohub.backend.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Staff-managed catalogue and owner-controlled selections; identifiers are server allowlisted. */
@RestController
public class AmenityController {
    private final AdminStore db;
    private final StaffAccessService access;
    public AmenityController(AdminStore db, StaffAccessService access) { this.db=db; this.access=access; }
    public record Create(@NotBlank @Size(max=80) String name) { }
    public record Selection(@jakarta.validation.constraints.NotNull @Size(max=50) Set<UUID> ids) { }
    private record Source(String table, String owner, String selection, String role) { }
    private Source source(String kind) {
        return switch(kind) {
            case "landlord" -> new Source("listings","landlord_id","landlord_listing_amenities","LANDLORD");
            case "sublessor" -> new Source("sublessor_listings","sublessor_id","sublessor_listing_amenities","SUBLESSOR");
            default -> throw AdminStore.bad("Unknown listing kind.");
        };
    }
    @GetMapping("/api/v1/amenities")
    public List<Map<String,Object>> catalogue() { return db.rows("SELECT id,name FROM amenity_catalogue ORDER BY lower(name),id"); }
    @GetMapping("/api/v1/admin/amenities")
    public List<Map<String,Object>> staffCatalogue(@AuthenticationPrincipal Jwt jwt) { access.require(jwt,StaffRole.Permission.STAFF); return catalogue(); }
    @PostMapping("/api/v1/admin/amenities") @Transactional
    public Map<String,Object> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Create request) {
        var staff=access.require(jwt,StaffRole.Permission.STAFF);
        String name=request.name().strip().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length()>80 || name.chars().anyMatch(Character::isISOControl)) throw AdminStore.bad("Invalid amenity name.");
        UUID id=UUID.randomUUID();
        try { db.update("INSERT INTO amenity_catalogue(id,name) VALUES (?,?)",id,name); }
        catch (DuplicateKeyException e) { throw AdminStore.conflict("Amenity already exists."); }
        db.audit(staff.userId(),"AMENITY_CREATED",id,"Added amenity",null,"CREATED");
        return Map.of("id",id,"name",name);
    }
    @GetMapping("/api/v1/{kind}/listings/{id}/amenities") @Transactional(readOnly=true)
    public List<Map<String,Object>> selected(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@PathVariable UUID id) {
        var source=source(kind); UUID owner=owner(jwt,source);
        requireActiveOwner(source,id,owner);
        return selectedRows(source,id);
    }
    @GetMapping("/api/v1/renter/listings/{kind}/{id}/amenities") @Transactional(readOnly=true)
    public List<Map<String,Object>> publicSelection(@PathVariable String kind,@PathVariable UUID id) {
        var source=source(kind);
        db.one("SELECT id FROM "+source.table()+" WHERE id=? AND status='PUBLISHED' AND moderation_status='APPROVED'",id);
        return selectedRows(source,id);
    }
    private List<Map<String,Object>> selectedRows(Source source,UUID id) {
        return db.rows("SELECT a.id,a.name FROM amenity_catalogue a JOIN "+source.selection()+" s ON s.amenity_id=a.id WHERE s.listing_id=? ORDER BY lower(a.name),a.id",id);
    }
    @PutMapping("/api/v1/{kind}/listings/{id}/amenities") @Transactional
    public List<Map<String,Object>> save(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@PathVariable UUID id,@Valid @RequestBody Selection request) {
        var source=source(kind); UUID owner=owner(jwt,source);
        requireActiveOwner(source,id,owner);
        var listing=db.one("SELECT status,moderation_submitted_at FROM "+source.table()+" WHERE id=? AND "+source.owner()+"=? FOR UPDATE",id,owner);
        if (!"DRAFT".equals(listing.get("status")) || listing.get("moderation_submitted_at")!=null)
            throw AdminStore.conflict("Revise the draft before changing amenities.");
        for (UUID amenity:request.ids()) if (amenity==null || !db.exists("SELECT EXISTS(SELECT 1 FROM amenity_catalogue WHERE id=?)",amenity)) throw AdminStore.bad("Unknown amenity.");
        db.update("DELETE FROM "+source.selection()+" WHERE listing_id=?",id);
        for (UUID amenity:request.ids()) db.update("INSERT INTO "+source.selection()+"(listing_id,amenity_id) VALUES (?,?)",id,amenity);
        return selectedRows(source,id);
    }
    private void requireActiveOwner(Source source,UUID id,UUID owner) {
        db.one("SELECT l.id FROM "+source.table()+" l JOIN users u ON u.id=l."+source.owner()+
                " JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE l.id=? AND u.id=? AND u.account_status='ACTIVE' AND r.name=?",id,owner,source.role());
    }
    private UUID owner(Jwt jwt, Source source) {
        if (!List.of(source.role()).equals(jwt.getClaimAsStringList("roles"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return UUID.fromString(jwt.getSubject());
    }
}
