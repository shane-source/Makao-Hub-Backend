package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.domain.*;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.RoleRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyRoleSelectionServiceTest {
    private final EntityManager em = mock(EntityManager.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final LegacyRoleSelectionService service = new LegacyRoleSelectionService(em, roles, userRoles);

    private User pendingUser() {
        User user = new User("Test User", "test@example.invalid", new byte[]{1}, "test-hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(user, "roleSelectionRequired", true);
        ReflectionTestUtils.setField(user, "authorizationVersion", 1L);
        return user;
    }

    @Test void requiresChoiceWithoutChangingState() {
        User user = pendingUser();
        AuthException error = assertThrows(AuthException.class, () -> service.resolve(user, null));
        assertEquals(AuthException.Reason.ROLE_SELECTION_REQUIRED, error.getReason());
        assertTrue(user.isRoleSelectionRequired());
        verifyNoInteractions(roles, userRoles);
    }

    @Test void rejectsAdminAndLandlordAsLegacyChoices() {
        for (RoleName role : new RoleName[]{RoleName.ADMIN, RoleName.LANDLORD}) {
            assertEquals(AuthException.Reason.INVALID_ROLE_SELECTION,
                    assertThrows(AuthException.class, () -> service.resolve(pendingUser(), role)).getReason());
        }
        verifyNoInteractions(roles, userRoles);
    }

    @Test void savesBothAllowedChoicesBeforeInvalidatingOldTokens() {
        for (RoleName name : new RoleName[]{RoleName.RENTER, RoleName.SUBLESSOR}) {
            User user = pendingUser();
            Role role = mock(Role.class);
            when(roles.findByName(name)).thenReturn(Optional.of(role));
            service.resolve(user, name);
            assertFalse(user.isRoleSelectionRequired());
            assertEquals(2L, user.getAuthorizationVersion());
            verify(em).refresh(user, LockModeType.PESSIMISTIC_WRITE);
            verify(userRoles).saveAndFlush(argThat(assignment -> assignment.getUser() == user
                    && assignment.getRole() == role));
        }
        verify(em, times(2)).flush();
    }

    @Test void cannotReactivateSuspendedUser() {
        User user = pendingUser();
        ReflectionTestUtils.setField(user, "accountStatus", AccountStatus.SUSPENDED);
        assertEquals(AuthException.Reason.ACCOUNT_UNAVAILABLE,
                assertThrows(AuthException.class, () -> service.resolve(user, RoleName.RENTER)).getReason());
        verifyNoInteractions(roles, userRoles);
    }

    @Test void cannotUseLoginChoiceAsGeneralRoleSwitch() {
        User user = pendingUser();
        ReflectionTestUtils.setField(user, "roleSelectionRequired", false);
        assertEquals(AuthException.Reason.ROLE_SELECTION_NOT_REQUIRED,
                assertThrows(AuthException.class, () -> service.resolve(user, RoleName.SUBLESSOR)).getReason());
        verifyNoInteractions(roles, userRoles);
    }
}
