package com.makaohub.backend.auth.repository;

import com.makaohub.backend.auth.domain.GoogleIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GoogleIdentityRepository
        extends JpaRepository<GoogleIdentity, UUID> {

    Optional<GoogleIdentity> findByGoogleSubject(
            String googleSubject
    );

    boolean existsByGoogleSubject(String googleSubject);
}