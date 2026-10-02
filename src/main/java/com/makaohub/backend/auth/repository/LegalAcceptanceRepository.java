package com.makaohub.backend.auth.repository;

import com.makaohub.backend.auth.domain.LegalAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LegalAcceptanceRepository
        extends JpaRepository<LegalAcceptance, UUID> {
}