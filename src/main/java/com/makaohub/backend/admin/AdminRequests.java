package com.makaohub.backend.admin;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class AdminRequests {
    private AdminRequests() { }
    public record Invite(@NotBlank @Email @Size(max=320) String email, @NotNull StaffRole role,
                         boolean documentReview, @NotBlank @Size(max=500) String reason) { }
    public record Accept(@NotBlank @Email @Size(max=320) String email, @NotBlank @Size(max=100) String invitationCode,
                         @NotBlank @Size(max=150) String fullName, @NotBlank @Size(min=8,max=72) String password) {
        @Override public String toString() { return "StaffInvitationAcceptance[redacted]"; }
    }
    public record StaffChange(@NotNull StaffRole role, boolean documentReview, boolean enabled,
                              @NotBlank @Size(max=500) String reason) { }
    public record Assign(@NotNull UUID reviewerId, @NotBlank @Size(max=500) String reason) { }
    public record Decision(boolean approve, @NotNull Instant expectedUpdatedAt, @NotBlank @Size(max=500) String reason) { }
    public record MessageAction(boolean escalate, @NotBlank @Size(max=500) String reason) { }
    public record Report(@NotBlank String subjectKind, @NotNull UUID subjectId, @NotBlank String category,
                         @NotBlank @Size(max=1000) String description) { }
    public record ReportAction(@NotBlank String state, @NotBlank String priority,
                               boolean pauseListing, boolean notifyUsers, @NotBlank @Size(max=500) String reason) { }
    public record AuditExport(UUID actor, UUID subject, @Min(0) @Max(10000) int page,
                              @Min(1) @Max(50) int size, @NotBlank @Size(max=500) String reason) { }
}
