package com.makaohub.backend.admin;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class ModerationSubmissionController {
    private final AdminWorkflowService work;
    private final UserReportService reports;
    public ModerationSubmissionController(AdminWorkflowService work,UserReportService reports) { this.work=work; this.reports=reports; }
    @PostMapping("/api/v1/landlord/listings/{id}/submit-review") public Map<String,Object> landlord(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return work.submitListing(jwt,"LANDLORD",id); }
    @PostMapping("/api/v1/sublessor/listings/{id}/submit-review") public Map<String,Object> sublessor(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return work.submitListing(jwt,"SUBLESSOR",id); }
    @GetMapping("/api/v1/landlord/listings/{id}/review") public Map<String,Object> landlordReview(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return work.ownListingReview(jwt,"LANDLORD",id); }
    @GetMapping("/api/v1/sublessor/listings/{id}/review") public Map<String,Object> sublessorReview(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return work.ownListingReview(jwt,"SUBLESSOR",id); }
    @PostMapping("/api/v1/landlord/listings/{id}/revise-review") public Map<String,Object> landlordRevise(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return work.reviseListing(jwt,"LANDLORD",id); }
    @PostMapping("/api/v1/sublessor/listings/{id}/revise-review") public Map<String,Object> sublessorRevise(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return work.reviseListing(jwt,"SUBLESSOR",id); }
    @PostMapping("/api/v1/reports") public Map<String,Object> report(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody AdminRequests.Report body) { return reports.create(jwt,body); }
}
