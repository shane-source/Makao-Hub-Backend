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

    public AuthOtpService(
            AuthOtpChallengeRepository challengeRepository,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            EmailOtpProperties properties,
            EmailOtpClient emailOtpClient,
            AuthenticationTokenService authenticationTokenService,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.otpKey = new SecretKeySpec(Base64.getDecoder().decode(properties.hmacKey()), "HmacSHA256");
        this.emailOtpClient = emailOtpClient;
        this.authenticationTokenService = authenticationTokenService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public OtpChallengeResponse startLogin(User user) {
        return issue(user, AuthOtpChallengePurpose.LOGIN);
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
        if (user.getAccountStatus() != AccountStatus.ACTIVE || user.getId() == null) throw invalidOtp();
        User current = userRepository.findByIdForUpdate(user.getId()).orElseThrow(this::invalidOtp);
        if (current.getAuthorizationVersion() != user.getAuthorizationVersion()
                || current.getAccountStatus() != AccountStatus.ACTIVE || current.isRoleSelectionRequired()) throw invalidOtp();
        Instant now = Instant.now(clock);
        if (challengeRepository.countByUser_IdAndPurposeAndIssuedAtAfter(current.getId(), purpose, now.minus(SEND_WINDOW)) >= MAX_SENDS_IN_WINDOW) {
            throw new AuthException(AuthException.Reason.OTP_RATE_LIMITED, "Too many verification codes were requested. Try again later.");
        }
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        AuthOtpChallenge challenge = new AuthOtpChallenge(current, purpose, current.getAuthorizationVersion(), now, now.plus(CHALLENGE_TTL));
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
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(otpKey);
            return HexFormat.of().formatHex(mac.doFinal(boundCode.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("OTP cryptography is unavailable.");
        }
    }


    private void ensureUsable(AuthOtpChallenge challenge, Instant now, AuthOtpChallengePurpose purpose) {
        if (challenge.getPurpose() != purpose || !challenge.isUsable(now)) {
            challenge.markExpired(now);
            throw invalidOtp();
        }
    }

    private void ensureUserCurrent(User user, AuthOtpChallenge challenge) {
        if (user.getAccountStatus() != AccountStatus.ACTIVE || user.isRoleSelectionRequired()
                || !user.getId().equals(challenge.getUser().getId())
                || user.getAuthorizationVersion() != challenge.getAuthorizationVersion()) throw invalidOtp();
    }

    private AuthException invalidOtp() { return new AuthException(AuthException.Reason.INVALID_OTP, "The verification code is invalid or expired."); }
    private static String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private static String mask(String email) { return "••••@" + email.substring(email.lastIndexOf('@') + 1); }

}
