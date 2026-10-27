package com.makaohub.backend.admin;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class StaffAccessService {
    private final AdminStore db;
    public StaffAccessService(AdminStore db) { this.db = db; }
    public record Staff(UUID userId, StaffRole role, boolean documents) { }

    public Staff require(Jwt jwt) {
        UUID user;
        long version;
        try {
            if (jwt == null) throw denied();
            user = UUID.fromString(jwt.getSubject());
            if (!List.of("ADMIN").equals(jwt.getClaimAsStringList("roles"))) { db.denied(user); throw denied(); }
            Object raw=jwt.getClaims().get("authorization_version");
            if (raw instanceof Long number) version=number;
            else if (raw instanceof Integer number) version=number.longValue();
            else throw denied();
        } catch (IllegalArgumentException failure) { throw denied(); }
        var matches = db.rows("""
                SELECT a.user_id,a.staff_role,a.can_view_documents FROM staff_accounts a
                JOIN users u ON u.id=a.user_id
                JOIN staff_sessions s ON s.user_id=a.user_id
                JOIN staff_access_proofs p ON p.session_id=s.id
                WHERE a.user_id=? AND a.enabled AND u.account_status='ACTIVE'
                  AND u.authorization_version=? AND NOT u.role_selection_required
                  AND p.token_digest=? AND p.expires_at>CURRENT_TIMESTAMP
                  AND s.expires_at>CURRENT_TIMESTAMP AND s.revoked_at IS NULL
                  AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.name='ADMIN')
                  AND EXISTS (SELECT 1 FROM refresh_tokens t WHERE t.token_family_id=s.token_family_id
                    AND t.revoked_at IS NULL AND t.used_at IS NULL AND t.expires_at>CURRENT_TIMESTAMP)
                """ + (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                        ? " FOR SHARE OF a,u" : ""), user, version, AdminStore.digest(jwt.getTokenValue()));
        if (matches.isEmpty()) { db.denied(user); throw denied(); }
        var row = matches.getFirst();
        return new Staff(user,StaffRole.valueOf((String)row.get("staff_role")),(boolean)row.get("can_view_documents"));
    }

    public Staff require(Jwt jwt, StaffRole.Permission permission) {
        Staff staff = require(jwt);
        if (!staff.role().permits(permission)) { db.denied(staff.userId()); throw denied(); }
        return staff;
    }

    public void requireAssignment(Staff staff, UUID subject, String role) {
        if (staff.role() == StaffRole.STAFF_LEAD) return;
        if (!staff.documents() || db.rows("SELECT reviewer_id FROM verification_assignments WHERE user_id=? AND role=? AND reviewer_id=? FOR SHARE",
                subject,role,staff.userId()).isEmpty()) { db.denied(staff.userId()); throw denied(); }
    }

    public boolean allows(Jwt jwt) {
        try { require(jwt); return true; }
        catch (ResponseStatusException failure) { return false; }
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "An active staff MFA session and the assigned permission are required.");
    }
}
