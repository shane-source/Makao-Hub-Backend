package com.makaohub.backend.verification.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Central server-side check for operations that require role verification. */
@Service
public class RoleVerificationGuard {
    private final RoleVerificationRepository repository;

    public RoleVerificationGuard(RoleVerificationRepository repository) {
        this.repository = repository;
    }

    public void requireVerified(UUID userId, RoleName role) {
        boolean verified = repository.findByUserIdAndRole(userId, role)
                .map(value -> value.getStatus() == VerificationStatus.VERIFIED)
                .orElse(false);
        if (!verified) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "The " + role.name().toLowerCase() + " role must be verified for this action."
            );
        }
    }
}
