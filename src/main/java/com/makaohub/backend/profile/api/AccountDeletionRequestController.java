package com.makaohub.backend.profile.api;

import com.makaohub.backend.admin.StaffAccessService;
import com.makaohub.backend.admin.StaffRole;
import com.makaohub.backend.profile.service.AccountDeletionRequestService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** The account owner submits/views a case; staff can only view the pending queue. */
@RestController
public class AccountDeletionRequestController {
    private final AccountDeletionRequestService requests;
    private final StaffAccessService staffAccess;

    public AccountDeletionRequestController(AccountDeletionRequestService requests, StaffAccessService staffAccess) {
        this.requests = requests;
        this.staffAccess = staffAccess;
    }

    @GetMapping("/api/v1/profile/me/deletion-request")
    public ResponseEntity<List<AccountDeletionRequestService.Request>> mine(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(requests.mine(subject(jwt)));
    }

    @GetMapping("/api/v1/admin/account-deletion-requests")
    public ResponseEntity<List<AccountDeletionRequestService.Request>> pending(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") int limit) {
        if (staffAccess.require(jwt).role() != StaffRole.STAFF_LEAD)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(requests.pendingForStaff(limit));
    }

    private UUID subject(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException failure) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    }
}
