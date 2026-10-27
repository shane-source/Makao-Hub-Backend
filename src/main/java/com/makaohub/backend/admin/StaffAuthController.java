package com.makaohub.backend.admin;

import com.makaohub.backend.auth.api.*;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/staff/auth")
public class StaffAuthController {
    private final StaffAuthService auth;
    private final StaffInvitationService invitations;
    public StaffAuthController(StaffAuthService auth,StaffInvitationService invitations) { this.auth=auth; this.invitations=invitations; }
    @PostMapping("/login") public OtpChallengeResponse login(@Valid @RequestBody LoginRequest body) { return auth.login(body); }
    @PostMapping("/verify-otp") public AuthTokensResponse verify(@Valid @RequestBody OtpVerifyRequest body) { return auth.verify(body); }
    @PostMapping("/refresh") public AuthTokensResponse refresh(@Valid @RequestBody RefreshTokenRequest body) { return auth.refresh(body.refreshToken()); }
    @PostMapping("/accept-invitation") public ResponseEntity<?> accept(@Valid @RequestBody AdminRequests.Accept body) {
        try { return ResponseEntity.ok(invitations.accept(body)); }
        catch (ResponseStatusException failure) {
            // Resolve expected failures here: sendError would redispatch to the protected /error route.
            String detail=switch (failure.getStatusCode().value()) {
                case 404 -> "This invitation could not be verified. Use the exact email address it was sent to, including any +suffix, and the newest invitation code. The code may have expired or already been used.";
                case 409 -> "This invitation can no longer be accepted. If you already created your staff account, sign in; otherwise contact your staff lead.";
                case 400 -> "Check the invitation details. Your password must contain at least 8 characters and no more than 72 UTF-8 bytes.";
                case 429 -> "Too many invitation attempts. Wait before trying again.";
                default -> "Staff account creation could not be completed. Please try again later.";
            };
            var problem=ProblemDetail.forStatusAndDetail(failure.getStatusCode(),detail);
            problem.setTitle("Staff invitation not accepted");
            problem.setProperty("code","STAFF_INVITATION_NOT_ACCEPTED");
            return ResponseEntity.status(failure.getStatusCode()).body(problem);
        }
    }
    @PostMapping("/logout") public void logout(@AuthenticationPrincipal Jwt jwt) { auth.logout(jwt); }
    @PostMapping("/forgot-password") public PasswordResetAcceptedResponse forgot(@Valid @RequestBody ForgotPasswordRequest body) { return auth.recover(body.email()); }
    @PostMapping("/reset-password") public void reset(@Valid @RequestBody ResetPasswordRequest body) { auth.reset(body); }
}
