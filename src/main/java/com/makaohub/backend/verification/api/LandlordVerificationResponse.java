package com.makaohub.backend.verification.api;

import com.makaohub.backend.verification.domain.VerificationStatus;

import java.time.Instant;

public record LandlordVerificationResponse(
        VerificationStatus status,
        Instant submittedAt,
        Instant reviewedAt,
        String rejectionReason
) {
}