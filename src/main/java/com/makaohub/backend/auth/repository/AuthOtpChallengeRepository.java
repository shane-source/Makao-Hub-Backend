package com.makaohub.backend.auth.repository;

import com.makaohub.backend.auth.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthOtpChallengeRepository extends JpaRepository<AuthOtpChallenge, UUID> {
    @Query("SELECT challenge.user.id FROM AuthOtpChallenge challenge WHERE challenge.id = :id")
    Optional<UUID> findUserIdByChallengeId(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT challenge FROM AuthOtpChallenge challenge WHERE challenge.id = :id")
    Optional<AuthOtpChallenge> findByIdForUpdate(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuthOtpChallenge> findFirstByUser_IdAndPurposeAndStatusOrderByIssuedAtDesc(UUID userId, AuthOtpChallengePurpose purpose, AuthOtpChallengeStatus status);
    long countByUser_IdAndPurposeAndIssuedAtAfter(UUID userId, AuthOtpChallengePurpose purpose, Instant issuedAfter);
}
