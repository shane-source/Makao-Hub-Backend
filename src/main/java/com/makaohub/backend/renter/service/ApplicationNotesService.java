package com.makaohub.backend.renter.service;

import com.makaohub.backend.admin.AdminStore;
import com.makaohub.backend.admin.StaffAccessService;
import com.makaohub.backend.admin.StaffRole;
import com.makaohub.backend.messaging.service.ChatContactMaskingService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Owner-only hiding and shared remarks; neither operation changes an application decision. */
@Service
public class ApplicationNotesService {
    public enum Kind {
        LANDLORD("rental_applications", "listings", "listing_id", "landlord_id", "landlord_application_id"),
        SUBLESSOR("sublessor_rental_applications", "sublessor_listings", "sublessor_listing_id", "sublessor_id", "sublessor_application_id");
        final String applications, listings, listingId, ownerId, noteId;
        Kind(String applications, String listings, String listingId, String ownerId, String noteId) {
            this.applications = applications; this.listings = listings; this.listingId = listingId; this.ownerId = ownerId; this.noteId = noteId;
        }
    }
    public record Note(UUID applicationId, boolean hidden, String remark) {
        @Override public String toString() { return "Note[redacted]"; }
    }
    public record StaffRemark(String listingTitle, String remark) {
        @Override public String toString() { return "StaffRemark[redacted]"; }
    }
    private final JdbcTemplate sql;
    private final ChatContactMaskingService masking;
    private final StaffAccessService staffAccess;
    private final AdminStore audit;

    public ApplicationNotesService(JdbcTemplate sql, ChatContactMaskingService masking, StaffAccessService staffAccess, AdminStore audit) {
        this.sql = sql; this.masking = masking; this.staffAccess = staffAccess; this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Note> ownerNotes(UUID owner, Kind kind) {
        requireRole(owner, kind.name());
        return sql.query("SELECT a.id, n.hidden, n.remark FROM " + kind.applications + " a JOIN " + kind.listings + " l ON l.id=a." + kind.listingId +
                " JOIN application_owner_notes n ON n." + kind.noteId + "=a.id WHERE l." + kind.ownerId + "=?",
                (rs, row) -> new Note(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getString(3)), owner);
    }

    @Transactional
    public void setHidden(UUID owner, Kind kind, UUID application, boolean hidden) {
        requireOwner(owner, kind, application);
        sql.update("INSERT INTO application_owner_notes (" + kind.noteId + ",hidden) VALUES (?,?) ON CONFLICT (" + kind.noteId + ") DO UPDATE SET hidden=EXCLUDED.hidden,updated_at=CURRENT_TIMESTAMP", application, hidden);
    }

    @Transactional
    public void setRemark(UUID owner, Kind kind, UUID application, String remark) {
        requireOwner(owner, kind, application);
        if (remark == null || remark.isBlank() || remark.strip().length() > 500 || masking.mask(remark).contactMasked()
                || remark.chars().filter(Character::isDigit).count() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a remark of up to 500 characters without contact or identity details.");
        }
        sql.update("INSERT INTO application_owner_notes (" + kind.noteId + ",remark) VALUES (?,?) ON CONFLICT (" + kind.noteId + ") DO UPDATE SET remark=EXCLUDED.remark,updated_at=CURRENT_TIMESTAMP", application, remark.strip());
    }

    @Transactional(readOnly = true)
    public String renterRemark(UUID renter, Kind kind, UUID application) {
        requireRole(renter, "RENTER");
        if (Boolean.FALSE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM " + kind.applications + " WHERE id=? AND renter_id=?)", Boolean.class, application, renter))) throw notFound();
        return remark(kind, application);
    }

    @Transactional
    public String staffRemark(Jwt jwt, Kind kind, UUID application) {
        var staff = staffAccess.require(jwt, StaffRole.Permission.VERIFICATION);
        var renters = sql.query("SELECT renter_id FROM " + kind.applications + " WHERE id=?", (rs, row) -> rs.getObject(1, UUID.class), application);
        if (renters.isEmpty()) throw notFound();
        staffAccess.requireAssignment(staff, renters.getFirst(), "RENTER");
        String result = remark(kind, application);
        audit.audit(staff.userId(), "APPLICATION_REMARK_VIEWED", application, "Assigned application review", null, "VIEWED");
        return result;
    }

    @Transactional
    public List<StaffRemark> staffRemarksForRenter(Jwt jwt, UUID renter) {
        var staff = staffAccess.require(jwt, StaffRole.Permission.VERIFICATION);
        staffAccess.requireAssignment(staff, renter, "RENTER");
        var result = sql.query("""
                SELECT l.title,n.remark FROM application_owner_notes n
                JOIN rental_applications a ON a.id=n.landlord_application_id
                JOIN listings l ON l.id=a.listing_id WHERE a.renter_id=? AND n.remark IS NOT NULL
                UNION ALL
                SELECT l.title,n.remark FROM application_owner_notes n
                JOIN sublessor_rental_applications a ON a.id=n.sublessor_application_id
                JOIN sublessor_listings l ON l.id=a.sublessor_listing_id WHERE a.renter_id=? AND n.remark IS NOT NULL
                """, (rs, row) -> new StaffRemark(rs.getString(1), rs.getString(2)), renter, renter);
        audit.audit(staff.userId(), "APPLICATION_REMARKS_VIEWED", renter, "Assigned renter review", null, "VIEWED");
        return result;
    }

    private String remark(Kind kind, UUID application) {
        var notes = sql.query("SELECT remark FROM application_owner_notes WHERE " + kind.noteId + "=?", (rs, row) -> rs.getString(1), application);
        return notes.isEmpty() ? null : notes.getFirst();
    }

    private void requireOwner(UUID owner, Kind kind, UUID application) {
        requireRole(owner, kind.name());
        if (Boolean.FALSE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM " + kind.applications + " a JOIN " + kind.listings + " l ON l.id=a." + kind.listingId + " WHERE a.id=? AND l." + kind.ownerId + "=?)", Boolean.class, application, owner))) throw notFound();
    }

    private void requireRole(UUID user, String role) {
        if (!Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.id=? AND u.account_status='ACTIVE' AND r.name=?)", Boolean.class, user, role))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account role unavailable.");
        }
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found."); }
}
