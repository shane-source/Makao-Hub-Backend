package com.makaohub.backend.auth.api;

import com.makaohub.backend.auth.domain.AuthOtpChallengePurpose;
import java.time.Instant;
import java.util.UUID;

public record OtpChallengeResponse(UUID challengeId, AuthOtpChallengePurpose purpose, String maskedEmail, Instant expiresAt) { }
