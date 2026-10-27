package com.makaohub.backend.admin;

import com.makaohub.backend.auth.api.*;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.RefreshTokenRepository;
import com.makaohub.backend.auth.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.makaohub.backend.auth.exception.OtpProviderException;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;

/** Reuses password validation, email delivery, OTP attempt limits and refresh-family reuse detection. */
@Service
public class StaffAuthService {
    private final AdminStore db;
    private final PasswordAuthenticationService passwords;
    private final AuthOtpService otp;
    private final AuthenticationTokenService tokens;
    private final RefreshTokenRepository tokenRows;
    private final StaffAccessService access;
    private final TransactionTemplate loginTransaction;
    public StaffAuthService(AdminStore db, PasswordAuthenticationService passwords, AuthOtpService otp,
            AuthenticationTokenService tokens, RefreshTokenRepository tokenRows, StaffAccessService access, PlatformTransactionManager transactions) {
        this.db=db; this.passwords=passwords; this.otp=otp; this.tokens=tokens; this.tokenRows=tokenRows; this.access=access;
        this.loginTransaction=new TransactionTemplate(transactions);
    }

    public OtpChallengeResponse login(LoginRequest request) {
        UUID actor=null;
        try {
            User user=passwords.verifyCredentials(request); actor=user.getId();
            return loginTransaction.execute(status -> {
                if (!activeStaff(user.getId())) throw invalid();
                OtpChallengeResponse challenge=otp.startLogin(user);
                tokenRows.flush();
                db.update("INSERT INTO staff_login_challenges(challenge_id,user_id) VALUES (?,?)",challenge.challengeId(),user.getId());
                db.audit(user.getId(),"STAFF_PASSWORD_VERIFIED",null,"Invited staff sign-in", "UNAUTHENTICATED","MFA_REQUIRED");
                return challenge;
            });
        } catch (AuthException failure) {
            db.audit(actor,"STAFF_LOGIN_FAILED",null,"Staff sign-in was not accepted",null,"DENIED"); throw failure;
        } catch (OtpProviderException failure) {
            db.audit(actor,"STAFF_LOGIN_DELIVERY_FAILED",null,"Email verification delivery was unavailable",null,"DENIED"); throw failure;
        }
    }

    @Transactional(noRollbackFor=AuthException.class)
    public AuthTokensResponse verify(OtpVerifyRequest request) {
        var marker=db.rows("SELECT user_id FROM staff_login_challenges WHERE challenge_id=?",request.parsedChallengeId());
        if (marker.isEmpty()) throw invalid();
        UUID user=(UUID)marker.getFirst().get("user_id");
        try {
            if (!activeStaff(user)) throw invalid();
            AuthTokensResponse response=otp.verifyLogin(request.parsedChallengeId(),request.code());
            tokenRows.flush();
            UUID family=(UUID)db.one("SELECT token_family_id FROM refresh_tokens WHERE token_hash=?",AdminStore.digest(response.refreshToken())).get("token_family_id");
            UUID session=UUID.randomUUID();
            // RECOMMENDED test-session ceiling: eight hours, never extended by refresh.
            db.update("INSERT INTO staff_sessions(id,user_id,token_family_id,expires_at) VALUES (?,?,?,CURRENT_TIMESTAMP+INTERVAL '8 hours')",session,user,family);
            proof(session,response);
            db.audit(user,"STAFF_MFA_VERIFIED",session,"Email OTP verified","MFA_REQUIRED","ACTIVE");
            return response;
        } catch (AuthException failure) {
            db.audit(user,"STAFF_MFA_FAILED",null,"Email OTP was not accepted",null,"DENIED"); throw failure;
        }
    }

    @Transactional(noRollbackFor=AuthException.class)
    public AuthTokensResponse refresh(String rawToken) {
        if (rawToken==null || rawToken.length()>200) throw invalid();
        // Same account-first lock order as the existing refresh/reset flows.
        User user=tokenRows.findAccountByTokenHashForUpdate(AdminStore.digest(rawToken)).orElseThrow(StaffAuthService::invalid);
        var sessions=db.rows("""
                SELECT s.id FROM staff_sessions s JOIN refresh_tokens t ON t.token_family_id=s.token_family_id
                WHERE t.token_hash=? AND s.user_id=? AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP FOR UPDATE OF s
                """,AdminStore.digest(rawToken),user.getId());
        if (sessions.isEmpty() || !activeStaff(user.getId())) throw invalid();
        // The existing rotate operation detects reuse and revokes the whole family.
        try {
            var response=tokens.refresh(rawToken);
            tokenRows.flush();
            proof((UUID)sessions.getFirst().get("id"),response);
            db.audit(user.getId(),"STAFF_SESSION_REFRESHED",null,"Refresh rotation", "ACTIVE","ACTIVE");
            return response;
        } catch (AuthException failure) {
            db.audit(user.getId(),"STAFF_REFRESH_FAILED",null,"Refresh rejected; reuse revokes its family",null,"DENIED"); throw failure;
        }
    }

    @Transactional
    public void logout(Jwt jwt) {
        var staff=access.require(jwt);
        var row=db.one("SELECT session_id FROM staff_access_proofs WHERE token_digest=?",AdminStore.digest(jwt.getTokenValue()));
        UUID session=(UUID)row.get("session_id");
        db.update("UPDATE staff_sessions SET revoked_at=CURRENT_TIMESTAMP WHERE id=?",session);
        db.update("UPDATE refresh_tokens SET revoked_at=CURRENT_TIMESTAMP,revocation_reason='STAFF_LOGOUT' WHERE token_family_id=(SELECT token_family_id FROM staff_sessions WHERE id=?) AND revoked_at IS NULL",session);
        db.audit(staff.userId(),"STAFF_LOGOUT",session,"Staff signed out","ACTIVE","REVOKED");
    }

    private boolean activeStaff(UUID user) {
        return db.exists("""
                SELECT EXISTS(SELECT 1 FROM staff_accounts a JOIN user_roles ur ON ur.user_id=a.user_id
                JOIN roles r ON r.id=ur.role_id WHERE a.user_id=? AND a.enabled AND r.name='ADMIN')
                """,user);
    }

    public PasswordResetAcceptedResponse recover(String email) {
        db.audit(null,"STAFF_RECOVERY_REQUESTED",null,"Staff account recovery requested",null,"REQUESTED");
        if (db.exists("SELECT EXISTS(SELECT 1 FROM staff_accounts a JOIN users u ON u.id=a.user_id WHERE lower(u.email)=? AND a.enabled AND u.account_status='ACTIVE')",email.trim().toLowerCase(Locale.ROOT)))
            return otp.requestPasswordReset(email);
        return new PasswordResetAcceptedResponse("If the account exists, a verification code has been sent.");
    }

    @Transactional(noRollbackFor=AuthException.class)
    public void reset(ResetPasswordRequest request) {
        var candidates=db.rows("SELECT a.user_id FROM staff_accounts a JOIN users u ON u.id=a.user_id WHERE lower(u.email)=? AND a.enabled AND u.account_status='ACTIVE'",request.email().trim().toLowerCase(Locale.ROOT));
        if (candidates.isEmpty()) throw invalid();
        UUID user=(UUID)candidates.getFirst().get("user_id");
        try {
            otp.resetPassword(request.email(),request.code(),request.newPassword());
            db.audit(user,"STAFF_PASSWORD_RESET",user,"Email recovery code verified","RECOVERY_PENDING","SIGN_IN_REQUIRED");
        } catch (AuthException failure) {
            db.audit(user,"STAFF_RECOVERY_FAILED",null,"Recovery code was not accepted",null,"DENIED"); throw failure;
        }
    }
    private void proof(UUID session,AuthTokensResponse response) {
        db.update("INSERT INTO staff_access_proofs(token_digest,session_id,expires_at) VALUES (?,?,?)",
                AdminStore.digest(response.accessToken()),session,java.sql.Timestamp.from(response.accessTokenExpiresAt()));
    }
    private static AuthException invalid() { return new AuthException(AuthException.Reason.INVALID_CREDENTIALS,"Staff credentials or verification are invalid."); }
}
