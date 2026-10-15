package com.makaohub.backend.profile.repository;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.profile.domain.RoleSwitchChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface RoleSwitchChallengeRepository
        extends JpaRepository<RoleSwitchChallenge, UUID> {

    long countByUserIdAndIssuedAtAfter(
            UUID userId,
            Instant cutoff
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE RoleSwitchChallenge challenge
            SET challenge.consumedAt = :now
            WHERE challenge.id = :challengeId
              AND challenge.userId = :userId
              AND challenge.targetRole = :targetRole
              AND challenge.authorizationVersion = :authorizationVersion
              AND challenge.nonceHash = :nonceHash
              AND challenge.consumedAt IS NULL
              AND challenge.issuedAt <= :now
              AND challenge.expiresAt > :now
            """)
    int consumeIfValid(
            @Param("challengeId") UUID challengeId,
            @Param("userId") UUID userId,
            @Param("targetRole") RoleName targetRole,
            @Param("authorizationVersion") long authorizationVersion,
            @Param("nonceHash") String nonceHash,
            @Param("now") Instant now
    );
}