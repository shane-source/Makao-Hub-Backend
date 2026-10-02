package com.makaohub.backend.profile.navigation;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.Role;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NavigationServiceTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "6b93d17b-0662-4718-a17d-736d85093e75"
            );

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private User user;

    @Mock
    private UserRole userRole;

    @Mock
    private Role role;

    private NavigationService navigationService;

    @BeforeEach
    void setUp() {
        navigationService = new NavigationService(
                userRepository,
                userRoleRepository
        );

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user));

        when(user.getAccountStatus())
                .thenReturn(AccountStatus.ACTIVE);

        when(userRoleRepository.findAllByUser_Id(USER_ID))
                .thenReturn(List.of(userRole));

        when(userRole.getRole()).thenReturn(role);
    }

    @Test
    void returnsExpectedNavigationForEveryPublicRole() {
        when(role.getName()).thenReturn(
                RoleName.RENTER,
                RoleName.LANDLORD,
                RoleName.ROOMMATE_SEEKER
        );

        assertNavigation(
                navigationService.getNavigation(USER_ID),
                RoleName.RENTER,
                List.of(
                        NavigationItemKey.HOME,
                        NavigationItemKey.SAVED,
                        NavigationItemKey.HUNT,
                        NavigationItemKey.INBOX,
                        NavigationItemKey.PROFILE
                ),
                List.of(
                        "/home",
                        "/saved",
                        "/hunt",
                        "/inbox",
                        "/profile"
                )
        );

        assertNavigation(
                navigationService.getNavigation(USER_ID),
                RoleName.LANDLORD,
                List.of(
                        NavigationItemKey.DASHBOARD,
                        NavigationItemKey.LISTINGS,
                        NavigationItemKey.INBOX,
                        NavigationItemKey.PROFILE
                ),
                List.of(
                        "/dashboard",
                        "/listings",
                        "/inbox",
                        "/profile"
                )
        );

        assertNavigation(
                navigationService.getNavigation(USER_ID),
                RoleName.ROOMMATE_SEEKER,
                List.of(
                        NavigationItemKey.HOME,
                        NavigationItemKey.REQUESTS,
                        NavigationItemKey.INBOX,
                        NavigationItemKey.PROFILE
                ),
                List.of(
                        "/home",
                        "/requests",
                        "/inbox",
                        "/profile"
                )
        );
    }

    private void assertNavigation(
            NavigationResponse response,
            RoleName expectedRole,
            List<NavigationItemKey> expectedKeys,
            List<String> expectedRoutes
    ) {
        assertEquals(expectedRole, response.role());

        assertEquals(
                expectedKeys,
                response.items().stream()
                        .map(NavigationItemResponse::key)
                        .toList()
        );

        assertEquals(
                expectedRoutes,
                response.items().stream()
                        .map(NavigationItemResponse::targetRoute)
                        .toList()
        );

        assertTrue(
                response.items().stream()
                        .allMatch(item -> item.badgeCount() == 0)
        );

        assertTrue(
                response.items().stream()
                        .allMatch(NavigationItemResponse::enabled)
        );

        assertTrue(
                response.items().stream()
                        .noneMatch(NavigationItemResponse::locked)
        );

        assertFalse(
                response.items().stream()
                        .anyMatch(item ->
                                item.key()
                                        == NavigationItemKey.HOUSE_HUNT
                        )
        );
    }
}