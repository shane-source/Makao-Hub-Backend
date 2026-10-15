package com.makaohub.backend.verification.domain;

import com.makaohub.backend.auth.domain.RoleName;
import java.io.Serializable;
import java.util.UUID;

public record RoleVerificationId(UUID userId, RoleName role) implements Serializable {
}
