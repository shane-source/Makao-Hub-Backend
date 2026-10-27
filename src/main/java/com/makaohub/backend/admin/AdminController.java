package com.makaohub.backend.admin;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminWorkflowService work;
    private final StaffInvitationService invites;
    private final StaffAccessService access;
    private final UserReportService reports;
    public AdminController(AdminWorkflowService work,StaffInvitationService invites,StaffAccessService access,UserReportService reports) {
        this.work=work; this.invites=invites; this.access=access; this.reports=reports;
    }

    /** Resolve expected rejections without sendError redispatching to the protected /error route. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> rejected(ResponseStatusException failure) {
        String detail=switch (failure.getStatusCode().value()) {
            case 400 -> "Check the submitted fields and use a short audit reason without private identity or contact details.";
            case 401 -> "Your staff session expired. Sign in again.";
            case 403 -> "Your staff permission or case assignment does not allow this action.";
            case 404 -> "The requested staff record was not found or is no longer available.";
            case 409 -> "This action conflicts with the current record. Reload and check its state before trying again.";
            case 429 -> "Too many requests. Wait before trying again.";
            default -> "The staff request could not be completed. Please try again later.";
        };
        // Only these fixed, non-sensitive reasons are exposed; never echo arbitrary exceptions.
        if (failure.getStatusCode().value()==409) {
            if ("Use a separate staff email; an existing account will not be promoted.".equals(failure.getReason()))
                detail="Use a separate staff email; an existing account will not be promoted.";
            else if ("Staff leads cannot change their own permissions here.".equals(failure.getReason()))
                detail="Staff leads cannot change their own permissions here.";
            else if (Set.of("View the current document before marking it reviewed.",
                    "View and mark every required current document reviewed before approval.",
                    "The record changed. Reload before deciding.",
                    "Only pending verification can be decided.",
                    "Older evidence must be replaced through the protected upload flow.")
                    .contains(Objects.toString(failure.getReason(),"")))
                detail=failure.getReason();
        }
        var problem=ProblemDetail.forStatusAndDetail(failure.getStatusCode(),detail);
        problem.setTitle("Staff request rejected");
        problem.setProperty("code","STAFF_REQUEST_REJECTED");
        return ResponseEntity.status(failure.getStatusCode()).header(HttpHeaders.CACHE_CONTROL,"no-store").body(problem);
    }
    @GetMapping("/me") public StaffAccessService.Staff me(@AuthenticationPrincipal Jwt jwt) { return access.require(jwt); }
    @GetMapping("/verifications") public AdminStore.Page verificationQueue(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String role,@RequestParam(defaultValue="PENDING") String state,
            @RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return work.verificationQueue(jwt,role,state,search,page,size);
    }
    @PostMapping("/verifications/{role}/{user}/assign") public Map<String,Object> assign(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,@PathVariable String role,@PathVariable UUID user,@Valid @RequestBody AdminRequests.Assign body) {
        return work.assign(jwt,key,user,role,body);
    }
    @GetMapping("/verifications/{role}/{user}") public Map<String,Object> verification(@AuthenticationPrincipal Jwt jwt,@PathVariable String role,@PathVariable UUID user) {
        return work.verificationCase(jwt,user,role);
    }
    @GetMapping("/verifications/{role}/{user}/documents/{document}") public ResponseEntity<byte[]> document(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String role,@PathVariable UUID user,@PathVariable UUID document) {
        var result=work.document(jwt,user,role,document);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CACHE_CONTROL,"no-store, private").header(HttpHeaders.PRAGMA,"no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\"verification-document\"")
                .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'")
                .body(result.bytes());
    }
    @PostMapping("/verifications/{role}/{user}/documents/{document}/review") public Map<String,Object> reviewed(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,@PathVariable String role,@PathVariable UUID user,@PathVariable UUID document) {
        return work.markDocumentReviewed(jwt,key,user,role,document);
    }
    @PostMapping("/verifications/{role}/{user}/decision") public Map<String,Object> verificationDecision(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,@PathVariable String role,@PathVariable UUID user,@Valid @RequestBody AdminRequests.Decision body) {
        return work.decideVerification(jwt,key,user,role,body);
    }
    @GetMapping("/listings") public AdminStore.Page listingQueue(@AuthenticationPrincipal Jwt jwt,@RequestParam String kind,
            @RequestParam(defaultValue="PENDING") String state,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return work.listings(jwt,kind,state,page,size);
    }
    @GetMapping("/listings/{kind}/{id}") public Map<String,Object> listing(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@PathVariable UUID id) { return work.listingDetail(jwt,kind,id); }
    @GetMapping("/listings/{kind}/{id}/media/{media}") public ResponseEntity<byte[]> listingMedia(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String kind,@PathVariable UUID id,@PathVariable UUID media) {
        var result=work.listingMedia(jwt,kind,id,media);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CACHE_CONTROL,"no-store, private").header(HttpHeaders.PRAGMA,"no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\"listing-media\"")
                .header("X-Content-Type-Options","nosniff").body(result.bytes());
    }
    @PostMapping("/listings/{kind}/{id}/decision") public Map<String,Object> listingDecision(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,@PathVariable String kind,@PathVariable UUID id,@Valid @RequestBody AdminRequests.Decision body) {
        return work.decideListing(jwt,key,kind,id,body);
    }
    @GetMapping("/flagged-messages") public AdminStore.Page flags(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return work.flags(jwt,page,size); }
    @PostMapping("/flagged-messages/{kind}/{id}/decision") public Map<String,Object> flag(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,@PathVariable String kind,@PathVariable UUID id,@Valid @RequestBody AdminRequests.MessageAction body) { return work.flagAction(jwt,key,kind,id,body); }
    @GetMapping("/reports") public AdminStore.Page reports(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="OPEN") String state,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return reports.queue(jwt,state,page,size); }
    @PostMapping("/reports/{id}/decision") public Map<String,Object> report(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,@Valid @RequestBody AdminRequests.ReportAction body) { return reports.act(jwt,key,id,body); }
    @GetMapping("/payments") public AdminStore.Page payments(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return work.payments(jwt,page,size); }
    @GetMapping("/bookings") public AdminStore.Page bookings(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return work.bookings(jwt,page,size); }
    @GetMapping("/audit") public AdminStore.Page audit(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) UUID actor,
            @RequestParam(required=false) UUID subject,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return work.audit(jwt,actor,subject,page,size); }
    @GetMapping("/staff") public AdminStore.Page staff(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return work.staff(jwt,page,size); }
    @GetMapping("/activity") public AdminStore.Page activity(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return work.activity(jwt,page,size); }
    @PostMapping("/audit/export") public ResponseEntity<Map<String,Object>> export(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody AdminRequests.AuditExport body) { return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(work.export(jwt,key,body)); }
    @PostMapping("/staff/invitations") public Map<String,Object> invite(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody AdminRequests.Invite body) { return invites.invite(jwt,key,body); }
    @PostMapping("/staff/{id}/permissions") public Map<String,Object> permissions(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,
            @PathVariable UUID id,@Valid @RequestBody AdminRequests.StaffChange body) { return invites.change(jwt,key,id,body); }
}
