package com.makaohub.backend.auth.repository;

import com.makaohub.backend.auth.domain.MfaLoginChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MfaLoginChallengeRepository
        extends JpaRepository<MfaLoginChallenge, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT challenge
            FROM MfaLoginChallenge challenge
            WHERE challenge.id = :challengeId
            """)
    Optional<MfaLoginChallenge> findByIdForUpdate(
            @Param("challengeId") UUID challengeId
    );
}