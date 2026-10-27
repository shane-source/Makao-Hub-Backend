package com.makaohub.backend.admin;

import com.makaohub.backend.auth.config.EmailOtpProperties;
import com.makaohub.backend.auth.service.EmailOtpClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Service
public class StaffInvitationService {
    private final AdminStore db;
    private final StaffAccessService access;
    private final EmailOtpClient email;
    private final PasswordEncoder passwords;
    private final SecretKeySpec key;
    public StaffInvitationService(AdminStore db, StaffAccessService access, EmailOtpClient email,
            PasswordEncoder passwords, EmailOtpProperties properties) {
        this.db=db; this.access=access; this.email=email; this.passwords=passwords;
        key=new SecretKeySpec(Base64.getDecoder().decode(properties.hmacKey()),"HmacSHA256");
    }

    @Transactional
    public Map<String,Object> invite(Jwt jwt,UUID command,AdminRequests.Invite request) {
        var staff=access.require(jwt,StaffRole.Permission.STAFF);
        return db.command(staff.userId(),command,"INVITE:"+emailDigest(request.email())+":"+request.role()+":"+request.documentReview()+":"+request.reason(),
                () -> issue(request.email(),request.role(),request.documentReview(),staff.userId(),AdminWorkflowService.reason(request.reason())));
    }

    public enum BootstrapResult { INVITATION_SENT, INVITATION_RESENT, INVITATION_PENDING, STAFF_EXISTS }

    /** Operator-only first setup; restarts retain existing staff/invitations without resending. */
    @Transactional
    public BootstrapResult bootstrap(String recipient) {
        managementLock();
        if (db.exists("SELECT EXISTS(SELECT 1 FROM staff_accounts)"))
            return BootstrapResult.STAFF_EXISTS;
        if (db.exists("SELECT EXISTS(SELECT 1 FROM staff_invitations WHERE expires_at>CURRENT_TIMESTAMP AND accepted_at IS NULL)"))
            return BootstrapResult.INVITATION_PENDING;
        issue(recipient,StaffRole.STAFF_LEAD,false,null,"Operator initialized the first dedicated staff lead");
        return BootstrapResult.INVITATION_SENT;
    }

    /** Explicit operator resend, restricted to the original first-lead recipient. */
    @Transactional
    public BootstrapResult resendBootstrap(String recipient) {
        managementLock();
        if (db.exists("SELECT EXISTS(SELECT 1 FROM staff_accounts)"))
            return BootstrapResult.STAFF_EXISTS;
        if (!EmailOtpProperties.validEmail(recipient))
            throw AdminStore.bad("Configure the original dedicated staff email before resending.");
        recipient=recipient.trim().toLowerCase(Locale.ROOT);
        if (db.exists("SELECT EXISTS(SELECT 1 FROM users WHERE lower(email)=?)",recipient))
            throw AdminStore.conflict("Use a separate staff email; an existing account will not be promoted.");
        var pending=db.rows("""
                SELECT id FROM staff_invitations WHERE invited_by IS NULL AND staff_role='STAFF_LEAD'
                AND email_digest=? AND accepted_at IS NULL ORDER BY issued_at DESC LIMIT 1 FOR UPDATE
                """,emailDigest(recipient));
        if (pending.isEmpty())
            throw AdminStore.conflict("No initial invitation matches the configured staff email. Use the original recipient.");
        UUID id=(UUID)pending.getFirst().get("id");
        // RECOMMENDED testing default: a persisted cooldown also covers concurrent processes.
        if (db.exists("""
                SELECT EXISTS(SELECT 1 FROM admin_audit_events WHERE subject_id=?
                AND action IN ('STAFF_INVITED','STAFF_INVITATION_RESENT')
                AND occurred_at>CURRENT_TIMESTAMP-INTERVAL '60 seconds')
                """,id))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"Wait 60 seconds before resending the staff invitation.");
        String code=invitationCode();
        db.update("UPDATE staff_invitations SET token_digest=?,expires_at=CURRENT_TIMESTAMP+INTERVAL '24 hours' WHERE id=?",
                AdminStore.digest(code),id);
        email.sendStaffInvitation(recipient,code);
        db.audit(null,"STAFF_INVITATION_RESENT",id,"Operator requested a replacement first-lead invitation code",
                "UNACCEPTED","INVITED");
        return BootstrapResult.INVITATION_RESENT;
    }

    private Map<String,Object> issue(String recipient,StaffRole role,boolean documents,UUID actor,String reason) {
        if (!EmailOtpProperties.validEmail(recipient) || (documents && role!=StaffRole.VERIFICATION_REVIEWER))
            throw AdminStore.bad("Choose a valid staff email and role-specific document permission.");
        recipient=recipient.trim().toLowerCase(Locale.ROOT);
        if (db.exists("SELECT EXISTS(SELECT 1 FROM users WHERE lower(email)=?)",recipient))
            throw AdminStore.conflict("Use a separate staff email; an existing account will not be promoted.");
        String code=invitationCode();
        UUID id=UUID.randomUUID();
        db.update("""
                INSERT INTO staff_invitations(id,email_digest,token_digest,staff_role,can_view_documents,invited_by,expires_at)
                VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP+INTERVAL '24 hours')
                """,id,emailDigest(recipient),AdminStore.digest(code),role.name(),documents,actor);
        email.sendStaffInvitation(recipient,code); // No URL token, raw-code persistence, logging or automatic retry.
        db.audit(actor,"STAFF_INVITED",id,reason,"NONE",role.name());
        return Map.of("invitationId",id,"state","INVITED");
    }

    @Transactional
    public Map<String,Object> accept(AdminRequests.Accept request) {
        managementLock();
        if (request.password().getBytes(StandardCharsets.UTF_8).length>72) throw AdminStore.bad("Password exceeds the encoder's byte limit.");
        var invitation=db.one("SELECT * FROM staff_invitations WHERE token_digest=? AND email_digest=? AND accepted_at IS NULL AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",
                AdminStore.digest(request.invitationCode()),emailDigest(request.email()));
        UUID inviter=(UUID)invitation.get("invited_by");
        if (inviter!=null && db.rows("SELECT a.user_id FROM staff_accounts a JOIN users u ON u.id=a.user_id WHERE a.user_id=? AND a.enabled AND a.staff_role='STAFF_LEAD' AND u.account_status='ACTIVE' FOR SHARE OF a,u",inviter).isEmpty())
            throw AdminStore.conflict("The invitation is no longer authorized.");
        String recipient=request.email().trim().toLowerCase(Locale.ROOT);
        if (db.exists("SELECT EXISTS(SELECT 1 FROM users WHERE lower(email)=?)",recipient)) throw AdminStore.conflict("This email already has an account.");
        UUID user=UUID.randomUUID();
        db.update("INSERT INTO users(id,email,full_name,password_hash) VALUES (?,?,?,?)",
                user,recipient,request.fullName().strip(),passwords.encode(request.password()));
        db.update("INSERT INTO user_roles(user_id,role_id,granted_by) SELECT ?,id,? FROM roles WHERE name='ADMIN'",user,inviter);
        db.update("INSERT INTO staff_accounts(user_id,staff_role,can_view_documents) VALUES (?,?,?)",
                user,invitation.get("staff_role"),invitation.get("can_view_documents"));
        db.update("UPDATE staff_invitations SET accepted_at=CURRENT_TIMESTAMP WHERE id=?",invitation.get("id"));
        db.audit(user,"STAFF_INVITATION_ACCEPTED",user,"Dedicated staff account created","INVITED","MFA_REQUIRED");
        return Map.of("userId",user,"nextStep","SIGN_IN_WITH_EMAIL_OTP"); // Never authenticate by invitation alone.
    }

    @Transactional
    public Map<String,Object> change(Jwt jwt,UUID command,UUID target,AdminRequests.StaffChange request) {
        var lead=access.require(jwt,StaffRole.Permission.STAFF);
        if (lead.userId().equals(target)) throw AdminStore.conflict("Staff leads cannot change their own permissions here.");
        if (request.documentReview() && request.role()!=StaffRole.VERIFICATION_REVIEWER) throw AdminStore.bad("Document permission belongs to verification reviewers only.");
        return db.command(lead.userId(),command,"STAFF_CHANGE:"+target+":"+request,() -> {
            managementLock();
            var previous=db.one("SELECT staff_role FROM staff_accounts WHERE user_id=? FOR UPDATE",target);
            db.update("UPDATE staff_accounts SET staff_role=?,can_view_documents=?,enabled=? WHERE user_id=?",
                    request.role().name(),request.documentReview(),request.enabled(),target);
            db.update("UPDATE users SET authorization_version=authorization_version+1 WHERE id=?",target);
            db.update("UPDATE staff_sessions SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND revoked_at IS NULL",target);
            db.update("UPDATE refresh_tokens SET revoked_at=CURRENT_TIMESTAMP,revocation_reason='STAFF_PERMISSION_CHANGE' WHERE user_id=? AND revoked_at IS NULL",target);
            db.audit(lead.userId(),"STAFF_PERMISSIONS_CHANGED",target,AdminWorkflowService.reason(request.reason()),
                    (String)previous.get("staff_role"),request.enabled()?request.role().name():"DISABLED");
            return Map.of("userId",target,"state",request.enabled()?request.role().name():"DISABLED");
        });
    }

    private void managementLock() {
        db.execute("SELECT pg_advisory_xact_lock(728162940315)");
    }

    private String invitationCode() {
        byte[] random=new byte[32];
        new SecureRandom().nextBytes(random);
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(random); }
        finally { Arrays.fill(random,(byte)0); }
    }
    private String emailDigest(String value) {
        try {
            var mac=Mac.getInstance("HmacSHA256"); mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(("staff-invitation-email:v1\n"+value.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("Invitation cryptography unavailable"); }
    }
}
