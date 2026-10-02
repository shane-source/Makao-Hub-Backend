package com.makaohub.backend.profile.navigation;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class NavigationService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    public NavigationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public NavigationResponse getNavigation(
            UUID authenticatedUserId
    ) {
        Objects.requireNonNull(
                authenticatedUserId,
                "Authenticated user ID cannot be null."
        );

        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(NavigationService::accountUnavailable);

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw accountUnavailable();
        }

        List<UserRole> userRoles =
                userRoleRepository.findAllByUser_Id(
                        authenticatedUserId
                );

        if (userRoles.size() != 1) {
            throw accountUnavailable();
        }

        RoleName role = userRoles.getFirst()
                .getRole()
                .getName();

        return new NavigationResponse(
                role,
                itemsFor(role)
        );
    }

    private List<NavigationItemResponse> itemsFor(
            RoleName role
    ) {
        return switch (role) {
            case RENTER -> List.of(
                    item(NavigationItemKey.HOME, "/home"),
                    item(NavigationItemKey.SAVED, "/saved"),
                    item(NavigationItemKey.HUNT, "/hunt"),
                    item(NavigationItemKey.INBOX, "/inbox"),
                    item(NavigationItemKey.PROFILE, "/profile")
            );

            case LANDLORD -> List.of(
                    item(
                            NavigationItemKey.DASHBOARD,
                            "/dashboard"
                    ),
                    item(
                            NavigationItemKey.LISTINGS,
                            "/listings"
                    ),
                    item(NavigationItemKey.INBOX, "/inbox"),
                    item(NavigationItemKey.PROFILE, "/profile")
            );

            case ROOMMATE_SEEKER -> List.of(
                    item(NavigationItemKey.HOME, "/home"),
                    item(
                            NavigationItemKey.REQUESTS,
                            "/requests"
                    ),
                    item(NavigationItemKey.INBOX, "/inbox"),
                    item(NavigationItemKey.PROFILE, "/profile")
            );

            case ADMIN -> List.of();
        };
    }

    private NavigationItemResponse item(
            NavigationItemKey key,
            String targetRoute
    ) {
        return new NavigationItemResponse(
                key,
                targetRoute,
                0,
                true,
                false
        );
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}