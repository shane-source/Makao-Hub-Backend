package com.makaohub.backend.verification.repository;

import com.makaohub.backend.verification.domain.LandlordVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LandlordVerificationRepository
        extends JpaRepository<LandlordVerification, UUID> {
}