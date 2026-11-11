package com.makaohub.backend.renter.api;

import com.makaohub.backend.renter.service.ApplicationNotesService;
import com.makaohub.backend.renter.service.ApplicationNotesService.Kind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
public class ApplicationNotesController {
    public record Hidden(@NotNull Boolean hidden) { }
    public record Remark(@NotBlank @Size(max = 500) String remark) {
        @Override public String toString() { return "Remark[redacted]"; }
    }
    public record RemarkResponse(String remark) {
        @Override public String toString() { return "RemarkResponse[redacted]"; }
    }
    private final ApplicationNotesService service;
    public ApplicationNotesController(ApplicationNotesService service) { this.service = service; }

    @GetMapping("/api/v1/{role:landlord|sublessor}/application-notes")
    public List<ApplicationNotesService.Note> ownerNotes(@AuthenticationPrincipal Jwt jwt, @PathVariable String role) {
        return service.ownerNotes(subject(jwt), kind(role));
    }
    @PutMapping("/api/v1/{role:landlord|sublessor}/applications/{id}/hidden")
    public void hidden(@AuthenticationPrincipal Jwt jwt, @PathVariable String role, @PathVariable UUID id, @Valid @RequestBody Hidden request) {
        service.setHidden(subject(jwt), kind(role), id, request.hidden());
    }
    @PutMapping("/api/v1/{role:landlord|sublessor}/applications/{id}/remark")
    public void remark(@AuthenticationPrincipal Jwt jwt, @PathVariable String role, @PathVariable UUID id, @Valid @RequestBody Remark request) {
        service.setRemark(subject(jwt), kind(role), id, request.remark());
    }
    @GetMapping("/api/v1/renter/{type:applications|sublessor-applications}/{id}/remark")
    public RemarkResponse renterRemark(@AuthenticationPrincipal Jwt jwt, @PathVariable String type, @PathVariable UUID id) {
        return new RemarkResponse(service.renterRemark(subject(jwt), type.equals("applications") ? Kind.LANDLORD : Kind.SUBLESSOR, id));
    }
    @GetMapping("/api/v1/admin/applications/{role:landlord|sublessor}/{id}/remark")
    public RemarkResponse staffRemark(@AuthenticationPrincipal Jwt jwt, @PathVariable String role, @PathVariable UUID id) {
        return new RemarkResponse(service.staffRemark(jwt, kind(role), id));
    }
    @GetMapping("/api/v1/admin/verifications/RENTER/{id}/application-remarks")
    public List<ApplicationNotesService.StaffRemark> staffRemarks(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.staffRemarksForRenter(jwt, id);
    }
    private static UUID subject(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (NullPointerException | IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account unavailable."); }
    }
    private static Kind kind(String role) { return role.equals("landlord") ? Kind.LANDLORD : Kind.SUBLESSOR; }
}
