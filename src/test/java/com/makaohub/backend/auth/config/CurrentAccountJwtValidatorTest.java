package com.makaohub.backend.auth.config;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentAccountJwtValidatorTest {
    private final UUID userId = UUID.randomUUID();
    private final UserRoleRepository roles = mock(UserRoleRepository.class);
    private final CurrentAccountJwtValidator validator = new CurrentAccountJwtValidator(roles);

    @BeforeEach
    void setUp() {
        when(roles.isCurrentAuthorization(userId, RoleName.RENTER, 2L)).thenReturn(true);
    }

    private Jwt token(String role, Object version) {
        var builder = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject(userId.toString()).claim("token_type", "access")
                .claim("roles", List.of(role));
        if (version != null) builder.claim("authorization_version", version);
        return builder.build();
    }

    @Test void acceptsCurrentRoleAndVersion() {
        assertFalse(validator.validate(token("RENTER", 2L)).hasErrors());
    }
    @Test void rejectsOldVersionEvenWhenRoleMatchesAgain() {
        assertTrue(validator.validate(token("RENTER", 1L)).hasErrors());
    }
    @Test void rejectsForgedOrStaleRole() {
        assertTrue(validator.validate(token("LANDLORD", 2L)).hasErrors());
    }
    @Test void rejectsRemovedRole() {
        assertTrue(validator.validate(token("ROOMMATE_SEEKER", 2L)).hasErrors());
    }
    @Test void rejectsAccountWhoseDatabaseAuthorizationFails() {
        when(roles.isCurrentAuthorization(userId, RoleName.RENTER, 2L)).thenReturn(false);
        assertTrue(validator.validate(token("RENTER", 2L)).hasErrors());
    }
    @Test void rejectsMissingVersionAfterAccountVersionChanges() {
        assertTrue(validator.validate(token("RENTER", null)).hasErrors());
    }
    @Test void allowsPreMigrationTokensOnlyForUnchangedVersionZeroAccounts() {
        when(roles.isCurrentAuthorization(userId, RoleName.RENTER, 0L)).thenReturn(true);
        assertFalse(validator.validate(token("RENTER", null)).hasErrors());
    }
    @Test void rejectsNonIntegralVersion() {
        assertTrue(validator.validate(token("RENTER", 2.5)).hasErrors());
    }
}
