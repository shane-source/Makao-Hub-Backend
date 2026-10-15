package com.makaohub.backend.profile.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.makaohub.backend.auth.api.AuthTokensResponse;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.Role;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.GoogleIdentityRepository;
import com.makaohub.backend.auth.repository.RefreshTokenRepository;
import com.makaohub.backend.auth.repository.RoleRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.auth.service.AuthenticationTokenService;
import com.makaohub.backend.auth.service.GoogleIdTokenVerifier;
import com.makaohub.backend.profile.api.GoogleRoleSwitchChallengeResponse;
import com.makaohub.backend.profile.api.SwitchRoleRequest;
import com.makaohub.backend.profile.domain.RoleSwitchAudit;
import com.makaohub.backend.profile.domain.RoleSwitchChallenge;
import com.makaohub.backend.profile.repository.RoleSwitchChallengeRepository;
import io.github.bucket4j.Bucket;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RoleSwitchService {

    private static final Duration CHALLENGE_TTL =
            Duration.ofMinutes(5);

    private static final Duration LIMIT_WINDOW =
            Duration.ofMinutes(15);

    private final SecureRandom random = new SecureRandom();

    // ponytail: single-instance limiter; use a shared store
    // before running multiple backend replicas.
    private final Cache<UUID, Bucket> attempts =
            Caffeine.newBuilder()
                    .maximumSize(50_000)
                    .expireAfterAccess(Duration.ofHours(1))
                    .build();

    private final EntityManager entityManager;
    private final UserRoleRepository assignments;
    private final RoleRepository roles;
    private final GoogleIdentityRepository identities;
    private final RoleSwitchChallengeRepository challenges;
    private final RefreshTokenRepository refreshTokens;
    private final GoogleIdTokenVerifier googleVerifier;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationTokenService tokenService;
    private final Clock clock;

    public RoleSwitchService(
            EntityManager entityManager,
            UserRoleRepository assignments,
            RoleRepository roles,
            GoogleIdentityRepository identities,
            RoleSwitchChallengeRepository challenges,
            RefreshTokenRepository refreshTokens,
            GoogleIdTokenVerifier googleVerifier,
            PasswordEncoder passwordEncoder,
            AuthenticationTokenService tokenService,
            Clock clock
    ) {
        this.entityManager = entityManager;
        this.assignments = assignments;
        this.roles = roles;
        this.identities = identities;
        this.challenges = challenges;
        this.refreshTokens = refreshTokens;
        this.googleVerifier = googleVerifier;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    @Transactional
    public GoogleRoleSwitchChallengeResponse issueGoogleChallenge(
            Jwt principal,
            RoleName targetRole
    ) {
        Caller caller = caller(principal);
        limitAttempts(caller.userId());

        User user = lockAccount(caller);
        requireSwitchableRole(user, targetRole);

        if (!identities.existsById(user.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Use password confirmation; no Google identity "
                            + "is linked to this account."
            );
        }

        Instant now = now();

        long recentChallenges =
                challenges.countByUserIdAndIssuedAtAfter(
                        user.getId(),
                        now.minus(LIMIT_WINDOW)
                );

        if (recentChallenges >= 5) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many confirmation challenges. Try again later."
            );
        }

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        String nonce = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        Instant expiresAt = now.plus(CHALLENGE_TTL);

        RoleSwitchChallenge challenge =
                challenges.saveAndFlush(
                        new RoleSwitchChallenge(
                                user.getId(),
                                targetRole,
                                user.getAuthorizationVersion(),
                                hash(nonce),
                                now,
                                expiresAt
                        )
                );

        return new GoogleRoleSwitchChallengeResponse(
                challenge.getId(),
                targetRole,
                nonce,
                expiresAt
        );
    }

    @Transactional
    public AuthTokensResponse switchRole(
            Jwt principal,
            SwitchRoleRequest request
    ) {
        Caller caller = caller(principal);
        limitAttempts(caller.userId());

        if (request == null
                || !request.isPublicRole()
                || !request.isSingleAuthenticationMethod()
                || !request.isPasswordWithinByteLimit()
                || (request.googleIdToken() != null
                && request.googleIdToken().length() > 8192)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid role switch request."
            );
        }

        User user = lockAccount(caller);

        UserRole oldAssignment = requireSwitchableRole(
                user,
                request.targetRole()
        );

        String method;

        if (request.currentPassword() != null) {
            if (user.getPasswordHash() == null
                    || !passwordEncoder.matches(
                    request.currentPassword(),
                    user.getPasswordHash()
            )) {
                throw confirmationFailed();
            }

            method = "PASSWORD";
        } else {
            confirmGoogle(user, request);
            method = "GOOGLE";
        }

        Role newRole = roles.findByName(
                request.targetRole()
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The requested role is not configured."
        ));

        RoleName previousRole =
                oldAssignment.getRole().getName();

        // Delete first: the database permits one active assignment.
        assignments.delete(oldAssignment);
        assignments.flush();

        assignments.saveAndFlush(
                new UserRole(user, newRole, null)
        );

        // V15 initializes this role's verification if none exists.
        user.invalidateAuthorization();

        Instant now = now();

        refreshTokens.revokeAllActiveForUser(
                user.getId(),
                now
        );

        entityManager.persist(
                new RoleSwitchAudit(
                        user.getId(),
                        previousRole,
                        request.targetRole(),
                        method,
                        user.getAuthorizationVersion(),
                        now
                )
        );

        entityManager.flush();

        return tokenService.issue(user);
    }

    private void confirmGoogle(
            User user,
            SwitchRoleRequest request
    ) {
        var linked = identities.findById(
                user.getId()
        ).orElseThrow(this::confirmationFailed);

        GoogleIdTokenVerifier.VerifiedRoleSwitchIdentity verified;

        try {
            verified = googleVerifier.verifyForRoleSwitch(
                    request.googleIdToken()
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw confirmationFailed();
        }

        Instant now = now();

        if (!linked.getGoogleSubject().equals(verified.subject())
                || !verified.expiresAt().isAfter(now)
                || verified.issuedAt().isAfter(now.plusSeconds(30))
                || verified.issuedAt().isBefore(
                now.minus(CHALLENGE_TTL).minusSeconds(30)
        )) {
            throw confirmationFailed();
        }

        int consumed = challenges.consumeIfValid(
                request.googleChallengeId(),
                user.getId(),
                request.targetRole(),
                user.getAuthorizationVersion(),
                hash(verified.nonce()),
                now
        );

        if (consumed != 1) {
            throw confirmationFailed();
        }
    }

    private User lockAccount(Caller caller) {
        User user = entityManager.find(
                User.class,
                caller.userId()
        );

        if (user == null) {
            throw confirmationFailed();
        }

        // Reload current state while acquiring the account lock.
        entityManager.refresh(
                user,
                LockModeType.PESSIMISTIC_WRITE
        );

        if (user.getAccountStatus() != AccountStatus.ACTIVE
                || user.isRoleSelectionRequired()
                || user.getAuthorizationVersion() != caller.version()) {
            throw confirmationFailed();
        }

        return user;
    }

    private UserRole requireSwitchableRole(
            User user,
            RoleName targetRole
    ) {
        if (!isPublicRole(targetRole)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Choose a public role."
            );
        }

        var current = assignments.findAllByUser_Id(
                user.getId()
        );

        if (current.size() != 1) {
            throw confirmationFailed();
        }

        UserRole assignment = current.getFirst();

        if (!isPublicRole(assignment.getRole().getName())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Staff accounts cannot use public role switching."
            );
        }

        if (assignment.getRole().getName() == targetRole) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This role is already active."
            );
        }

        return assignment;
    }

    private static boolean isPublicRole(RoleName role) {
        return role == RoleName.LANDLORD
                || role == RoleName.RENTER
                || role == RoleName.SUBLESSOR;
    }

    private Caller caller(Jwt jwt) {
        try {
            if (jwt == null
                    || !"access".equals(
                    jwt.getClaimAsString("token_type")
            )) {
                throw confirmationFailed();
            }

            Object claim = jwt.getClaims().get(
                    "authorization_version"
            );

            long version;

            if (claim == null) {
                version = 0;
            } else if (claim instanceof Long value) {
                version = value;
            } else if (claim instanceof Integer value) {
                version = value.longValue();
            } else {
                throw confirmationFailed();
            }

            if (version < 0) {
                throw confirmationFailed();
            }

            return new Caller(
                    UUID.fromString(jwt.getSubject()),
                    version
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw confirmationFailed();
        }
    }

    private void limitAttempts(UUID userId) {
        Bucket bucket = attempts.get(
                userId,
                ignored -> Bucket.builder()
                        .addLimit(limit -> limit
                                .capacity(10)
                                .refillGreedy(10, LIMIT_WINDOW)
                        )
                        .build()
        );

        if (!bucket.tryConsume(1)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many role confirmation attempts. "
                            + "Try again later."
            );
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(
                ChronoUnit.MICROS
        );
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance(
                    "SHA-256"
            ).digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable.",
                    exception
            );
        }
    }

    private AuthException confirmationFailed() {
        return new AuthException(
                AuthException.Reason.INVALID_CREDENTIALS,
                "Role confirmation failed. Sign in again "
                        + "or request a new challenge."
        );
    }

    private record Caller(
            UUID userId,
            long version
    ) {
    }
}