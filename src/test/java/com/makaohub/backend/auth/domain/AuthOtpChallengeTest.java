package com.makaohub.backend.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthOtpChallengeTest {

    @Test
    void fiveFailedAttemptsLockChallengeAndReuseIsImpossible() {
        Instant issued = Instant.parse("2026-10-12T09:00:00Z");
        User user = new User(
                "Test User",
                "test@example.invalid",
                new byte[]{1, 2, 3},
                "lookup-hash"
        );
        AuthOtpChallenge challenge = new AuthOtpChallenge(
                user,
                AuthOtpChallengePurpose.LOGIN,
                0,
                issued,
                issued.plusSeconds(300)
        );
        challenge.setCodeDigest("a".repeat(64));

        for (int attempt = 0; attempt < AuthOtpChallenge.MAX_ATTEMPTS; attempt++) {
            challenge.recordFailedAttempt(issued.plusSeconds(attempt + 1));
        }

        assertEquals(AuthOtpChallengeStatus.LOCKED, challenge.getStatus());
        assertFalse(challenge.isUsable(issued.plusSeconds(10)));
    }

    @Test
    void verifiedChallengeCannotBeVerifiedTwice() {
        Instant issued = Instant.parse("2026-10-12T09:00:00Z");
        User user = new User(
                "Test User",
                "test2@example.invalid",
                new byte[]{1, 2, 3},
                "lookup-hash-2"
        );
        AuthOtpChallenge challenge = new AuthOtpChallenge(
                user,
                AuthOtpChallengePurpose.PASSWORD_RESET,
                0,
                issued,
                issued.plusSeconds(300)
        );
        challenge.setCodeDigest("b".repeat(64));

        challenge.markVerified(issued.plusSeconds(1));

        assertEquals(AuthOtpChallengeStatus.VERIFIED, challenge.getStatus());
        assertFalse(challenge.isUsable(issued.plusSeconds(2)));
        assertTrue(challenge.getVerifiedAt().isAfter(issued));
    }
}
