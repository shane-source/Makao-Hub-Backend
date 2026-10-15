package com.makaohub.backend.verification.repository;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.verification.domain.RoleVerification;
import com.makaohub.backend.verification.domain.RoleVerificationId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;

public interface RoleVerificationRepository
        extends JpaRepository<RoleVerification, RoleVerificationId> {
    Optional<RoleVerification> findByUserIdAndRole(UUID userId, RoleName role);
}
