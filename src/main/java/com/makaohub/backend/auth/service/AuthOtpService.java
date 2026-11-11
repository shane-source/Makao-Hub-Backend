package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.api.AuthTokensResponse;
import com.makaohub.backend.auth.api.PasswordResetAcceptedResponse;
import com.makaohub.backend.auth.api.OtpChallengeResponse;
import com.makaohub.backend.auth.config.EmailOtpProperties;
import com.makaohub.backend.auth.domain.*;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.exception.OtpProviderException;
import com.makaohub.backend.auth.repository.AuthOtpChallengeRepository;
import com.makaohub.backend.auth.repository.RefreshTokenRepository;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.GoogleIdentityRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.Base64;
import java.util.HexFormat;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class AuthOtpService {
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Duration SEND_WINDOW = Duration.ofMinutes(15);
    private static final long MAX_SENDS_IN_WINDOW = 3;
    private static final String RESET_REVOKE_REASON = "PASSWORD_RESET";

    private final AuthOtpChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecretKeySpec otpKey;
    private final SecureRandom random = new SecureRandom();
    private final EmailOtpClient emailOtpClient;
    private final AuthenticationTokenService authenticationTokenService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final GoogleIdentityRepository googleIdentities;

    public AuthOtpService(
            AuthOtpChallengeRepository challengeRepository,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            EmailOtpProperties properties,
            EmailOtpClient emailOtpClient,
            AuthenticationTokenService authenticationTokenService,
            PasswordEncoder passwordEncoder,
            Clock clock,
            GoogleIdentityRepository googleIdentities
    ) {
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.otpKey = new SecretKeySpec(Base64.getDecoder().decode(properties.hmacKey()), "HmacSHA256");
        this.emailOtpClient = emailOtpClient;
        this.authenticationTokenService = authenticationTokenService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.googleIdentities = googleIdentities;
    }

    @Transactional
    public OtpChallengeResponse startLogin(User user) {
        return issue(user, user.getAccountStatus() == AccountStatus.DEACTIVATED
                ? AuthOtpChallengePurpose.REACTIVATION : AuthOtpChallengePurpose.LOGIN);
    }

    @Transactional
    public OtpChallengeResponse startGoogleLogin(User user, String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS, "Google authentication failed.");
        }
        return issue(user, user.getAccountStatus() == AccountStatus.DEACTIVATED
                ? AuthOtpChallengePurpose.REACTIVATION : AuthOtpChallengePurpose.LOGIN, subject);
    }

    @Transactional
    public OtpChallengeResponse startAccountAction(User user, AuthOtpChallengePurpose purpose) {
        if (purpose != AuthOtpChallengePurpose.ACCOUNT_DEACTIVATION
                && purpose != AuthOtpChallengePurpose.ACCOUNT_DELETION) throw invalidOtp();
        return issue(user, purpose);
    }

    /** The caller owns the state change in the same transaction after checking financial blockers. */
    @Transactional(noRollbackFor = AuthException.class)
    public User verifyAccountAction(UUID userId, UUID challengeId, String code, AuthOtpChallengePurpose purpose) {
        if (purpose != AuthOtpChallengePurpose.ACCOUNT_DEACTIVATION
                && purpose != AuthOtpChallengePurpose.ACCOUNT_DELETION
                || !userId.equals(challengeRepository.findUserIdByChallengeId(challengeId).orElse(null))) throw invalidOtp();
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(this::invalidOtp);
        AuthOtpChallenge challenge = challengeRepository.findByIdForUpdate(challengeId).orElseThrow(this::invalidOtp);
        Instant now = Instant.now(clock);
        ensureUsable(challenge, now, purpose);
        ensureUserCurrent(user, challenge);
        verifyCodeOrFail(challenge, user, code, now);
        challenge.markVerified(now);
        return user;
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthTokensResponse verifyReactivation(UUID challengeId, String code) {
        UUID userId = challengeRepository.findUserIdByChallengeId(challengeId).orElseThrow(this::invalidOtp);
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(this::invalidOtp);
        AuthOtpChallenge challenge = challengeRepository.findByIdForUpdate(challengeId).orElseThrow(this::invalidOtp);
        Instant now = Instant.now(clock);
        ensureUsable(challenge, now, AuthOtpChallengePurpose.REACTIVATION);
        ensureUserCurrent(user, challenge);
        verifyCodeOrFail(challenge, user, code, now);
        if (challenge.getPendingGoogleSubject() != null) {
            ensureGoogleLinkAvailable(user, challenge.getPendingGoogleSubject());
            if (!googleIdentities.existsById(user.getId()))
                googleIdentities.saveAndFlush(new GoogleIdentity(user, challenge.getPendingGoogleSubject()));
        }
        challenge.markVerified(now);
        user.changeSelfServiceStatus(AccountStatus.ACTIVE);
        return authenticationTokenService.issue(user);
    }

    @Transactional
    public OtpChallengeResponse startRegistration(User user) {
        return issue(user, AuthOtpChallengePurpose.REGISTRATION);
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthTokensResponse verifyLogin(UUID challengeId, String code) {
        return verifyAndIssueTokens(challengeId, code, AuthOtpChallengePurpose.LOGIN);
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthTokensResponse verifyRegistration(UUID challengeId, String code) {
        return verifyAndIssueTokens(challengeId, code, AuthOtpChallengePurpose.REGISTRATION);
    }

    private AuthTokensResponse verifyAndIssueTokens(
            UUID challengeId,
            String code,
            AuthOtpChallengePurpose purpose
    ) {
        // Same lock order as issuance/reset: user first, challenge second.
        UUID userId = challengeRepository.findUserIdByChallengeId(challengeId).orElseThrow(this::invalidOtp);
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(this::invalidOtp);
        AuthOtpChallenge challenge = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(this::invalidOtp);
        Instant now = Instant.now(clock);
        ensureUsable(challenge, now, purpose);
        ensureUserCurrent(user, challenge);
        verifyCodeOrFail(challenge, user, code, now);
        if (challenge.getPendingGoogleSubject() != null) {
            ensureGoogleLinkAvailable(user, challenge.getPendingGoogleSubject());
            if (!googleIdentities.existsById(user.getId())) {
                // User-row lock serializes linking; unique subject/user constraints prevent reassignment.
                googleIdentities.saveAndFlush(new GoogleIdentity(user, challenge.getPendingGoogleSubject()));
            }
        }
        challenge.markVerified(now);
        return authenticationTokenService.issue(user);
    }

    @Transactional
    public PasswordResetAcceptedResponse requestPasswordReset(String email) {
        String normalized = normalizeEmail(email);
        userRepository.findByEmailIgnoreCaseForUpdate(normalized).ifPresent(user -> {
            if (user.getAccountStatus() == AccountStatus.ACTIVE && user.getPasswordHash() != null) {
                try {
                    issue(user, AuthOtpChallengePurpose.PASSWORD_RESET);
                } catch (OtpProviderException | IllegalStateException | AuthException ignored) {
                    // Same neutral response for absent accounts, quotas and email outages.
                }
            }
        });
        return new PasswordResetAcceptedResponse("If the account exists, a verification code has been sent.");
    }

    @Transactional(noRollbackFor = AuthException.class)
    public void resetPassword(String email, String code, String newPassword) {
        if (newPassword == null || newPassword.isBlank() || newPassword.length() < 8
                || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) throw invalidOtp();
        User user = userRepository.findByEmailIgnoreCaseForUpdate(normalizeEmail(email))
                .orElseThrow(this::invalidOtp);
        AuthOtpChallenge challenge = challengeRepository
                .findFirstByUser_IdAndPurposeAndStatusOrderByIssuedAtDesc(
                        user.getId(), AuthOtpChallengePurpose.PASSWORD_RESET, AuthOtpChallengeStatus.PENDING)
                .orElseThrow(this::invalidOtp);
        Instant now = Instant.now(clock);
        ensureUsable(challenge, now, AuthOtpChallengePurpose.PASSWORD_RESET);
        ensureUserCurrent(user, challenge);
        verifyCodeOrFail(challenge, user, code, now);
        challenge.markVerified(now);
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        refreshTokenRepository.revokeAllActiveForUserWithReason(user.getId(), now, RESET_REVOKE_REASON);
    }

    private OtpChallengeResponse issue(User user, AuthOtpChallengePurpose purpose) {
        return issue(user, purpose, null);
    }

    private OtpChallengeResponse issue(User user, AuthOtpChallengePurpose purpose, String googleSubject) {
        AccountStatus requiredStatus = purpose == AuthOtpChallengePurpose.REACTIVATION
                ? AccountStatus.DEACTIVATED : AccountStatus.ACTIVE;
        if (user.getAccountStatus() != requiredStatus || user.getId() == null) throw invalidOtp();
        User current = userRepository.findByIdForUpdate(user.getId()).orElseThrow(this::invalidOtp);
        if (current.getAuthorizationVersion() != user.getAuthorizationVersion()
                || current.getAccountStatus() != requiredStatus || current.isRoleSelectionRequired()) throw invalidOtp();
        if (googleSubject != null) ensureGoogleLinkAvailable(current, googleSubject);
        Instant now = Instant.now(clock);
        if (challengeRepository.countByUser_IdAndPurposeAndIssuedAtAfter(current.getId(), purpose, now.minus(SEND_WINDOW)) >= MAX_SENDS_IN_WINDOW) {
            throw new AuthException(AuthException.Reason.OTP_RATE_LIMITED, "Too many verification codes were requested. Try again later.");
        }
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        AuthOtpChallenge challenge = new AuthOtpChallenge(current, purpose, current.getAuthorizationVersion(), now, now.plus(CHALLENGE_TTL));
        if (googleSubject != null) challenge.bindGoogleSubject(googleSubject);
        challenge.setCodeDigest(digest(challenge, current, code));
        // Only supersede the previous challenge after SMTP accepted the email.
        // No automatic retry: a timeout may conceal acceptance.
        emailOtpClient.sendCode(current.getEmail(), code, purpose);
        challengeRepository
                .findFirstByUser_IdAndPurposeAndStatusOrderByIssuedAtDesc(
                        current.getId(), purpose, AuthOtpChallengeStatus.PENDING)
                .ifPresent(AuthOtpChallenge::supersede);
        challengeRepository.save(challenge);
        return new OtpChallengeResponse(challenge.getId(), purpose, mask(current.getEmail()), challenge.getExpiresAt());
    }

    private void verifyCodeOrFail(AuthOtpChallenge challenge, User user, String code, Instant now) {
        if (code == null || !code.matches("[0-9]{6}") || !MessageDigest.isEqual(
                HexFormat.of().parseHex(challenge.getCodeDigest()), HexFormat.of().parseHex(digest(challenge, user, code)))) {
            challenge.recordFailedAttempt(now);
            throw invalidOtp();
        }
    }

    private String digest(AuthOtpChallenge challenge, User user, String code) {
        String boundCode = "makao:email-otp:v1\n" + challenge.getId() + "\n" + user.getId() + "\n"
                + challenge.getPurpose() + "\n" + challenge.getAuthorizationVersion() + "\n" + user.getEmail() + "\n" + code;
        // Preserve existing outstanding codes, but bind new Google challenges to the verified subject.
        if (challenge.getPendingGoogleSubject() != null) boundCode += "\ngoogle:" + challenge.getPendingGoogleSubject();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(otpKey);
            return HexFormat.of().formatHex(mac.doFinal(boundCode.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("OTP cryptography is unavailable.");
        }
    }

    private void ensureGoogleLinkAvailable(User user, String subject) {
        var byUser = googleIdentities.findById(user.getId());
        var bySubject = googleIdentities.findByGoogleSubject(subject);
        if (byUser.filter(link -> !link.getGoogleSubject().equals(subject)).isPresent()
                || bySubject.filter(link -> !link.getUser().getId().equals(user.getId())).isPresent()) {
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS,
                    "Google authentication failed. Use your existing sign-in method.");
        }
    }


    private void ensureUsable(AuthOtpChallenge challenge, Instant now, AuthOtpChallengePurpose purpose) {
        if (challenge.getPurpose() != purpose || !challenge.isUsable(now)) {
            challenge.markExpired(now);
            throw invalidOtp();
        }
    }

    private void ensureUserCurrent(User user, AuthOtpChallenge challenge) {
        AccountStatus requiredStatus = challenge.getPurpose() == AuthOtpChallengePurpose.REACTIVATION
                ? AccountStatus.DEACTIVATED : AccountStatus.ACTIVE;
        if (user.getAccountStatus() != requiredStatus || user.isRoleSelectionRequired()
                || !user.getId().equals(challenge.getUser().getId())
                || user.getAuthorizationVersion() != challenge.getAuthorizationVersion()) throw invalidOtp();
    }

    private AuthException invalidOtp() { return new AuthException(AuthException.Reason.INVALID_OTP, "The verification code is invalid or expired."); }
    private static String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private static String mask(String email) { return "••••@" + email.substring(email.lastIndexOf('@') + 1); }

}
