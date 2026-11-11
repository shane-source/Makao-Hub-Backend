package com.makaohub.backend.profile.service;

import com.makaohub.backend.auth.api.OtpChallengeResponse;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.AuthOtpChallengePurpose;
import com.makaohub.backend.auth.repository.RefreshTokenRepository;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.service.AuthOtpService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Email-confirmed self-service transitions, never financial record erasure. */
@Service
public class AccountLifecycleService {
    private final UserRepository users;
    private final AuthOtpService otp;
    private final RefreshTokenRepository refreshTokens;
    private final AccountDeletionRequestService deletionRequests;
    private final JdbcTemplate sql;
    private final Clock clock;

    public AccountLifecycleService(UserRepository users, AuthOtpService otp,
            RefreshTokenRepository refreshTokens, AccountDeletionRequestService deletionRequests,
            JdbcTemplate sql, Clock clock) {
        this.users = users;
        this.otp = otp;
        this.refreshTokens = refreshTokens;
        this.deletionRequests = deletionRequests;
        this.sql = sql;
        this.clock = clock;
    }

    @Transactional
    public OtpChallengeResponse start(UUID userId, String email, AuthOtpChallengePurpose purpose) {
        var user = users.findByIdForUpdate(userId).orElseThrow(AccountLifecycleService::unavailable);
        requireActiveAndMatchingEmail(user.getAccountStatus(), user.getEmail(), email);
        requireNoOpenMoney(userId);
        return otp.startAccountAction(user, purpose);
    }

    @Transactional(noRollbackFor = com.makaohub.backend.auth.exception.AuthException.class)
    public void confirm(UUID userId, String email, UUID challengeId, String code,
            AuthOtpChallengePurpose purpose) {
        var user = otp.verifyAccountAction(userId, challengeId, code, purpose);
        requireActiveAndMatchingEmail(user.getAccountStatus(), user.getEmail(), email);
        requireNoOpenMoney(userId);
        if (purpose == AuthOtpChallengePurpose.ACCOUNT_DELETION) {
            // The request remains reviewable; no identity, booking or ledger row is erased here.
            deletionRequests.request(userId);
            user.changeSelfServiceStatus(AccountStatus.DELETION_PENDING);
        } else if (purpose == AuthOtpChallengePurpose.ACCOUNT_DEACTIVATION) {
            user.changeSelfServiceStatus(AccountStatus.DEACTIVATED);
        } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        refreshTokens.revokeAllActiveForUserWithReason(userId, Instant.now(clock), "SELF_SERVICE_ACCOUNT_STATUS");
    }

    private void requireNoOpenMoney(UUID userId) {
        Boolean blocked = sql.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM bookings b WHERE (b.renter_id=? OR b.owner_id=?)
                    AND b.status IN ('AWAITING_PAYMENT','PAYMENT_PENDING','PAID_HELD','ACTIVE')
            ) OR EXISTS (
                SELECT 1 FROM payment_intents p WHERE p.payer_user_id=?
                    AND p.status IN ('CREATED','PENDING','REFUND_PENDING','DISPUTED')
            ) OR EXISTS (
                SELECT 1 FROM paystack_test_transfers t WHERE t.owner_id=?
                    AND t.state IN ('QUEUED','UNKNOWN','PENDING','OTP','REVIEW')
            )
            """, Boolean.class, userId, userId, userId, userId);
        if (Boolean.TRUE.equals(blocked)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Resolve active bookings, held payments, pending payouts or disputes first.");
    }

    private void requireActiveAndMatchingEmail(AccountStatus status, String stored, String supplied) {
        if (status != AccountStatus.ACTIVE || supplied == null || !stored.equalsIgnoreCase(supplied.strip()))
            throw unavailable();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Account action is unavailable.");
    }
}
