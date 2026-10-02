package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.api.AuthTokensResponse;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class AuthenticationTokenService {

    private final UserRoleRepository userRoleRepository;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;

    public AuthenticationTokenService(
            UserRoleRepository userRoleRepository,
            AccessTokenService accessTokenService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRoleRepository = userRoleRepository;
        this.accessTokenService = accessTokenService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthTokensResponse issue(User user) {
        RefreshTokenService.IssuedRefreshToken refreshToken =
                refreshTokenService.issue(user);

        return createResponse(
                user,
                refreshToken
        );
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthTokensResponse refresh(String rawRefreshToken) {
        RefreshTokenService.RotatedRefreshToken rotatedToken =
                refreshTokenService.rotate(rawRefreshToken);

        return createResponse(
                rotatedToken.user(),
                rotatedToken.refreshToken()
        );
    }

    private AuthTokensResponse createResponse(
            User user,
            RefreshTokenService.IssuedRefreshToken refreshToken
    ) {
        Objects.requireNonNull(user, "User cannot be null.");
        Objects.requireNonNull(
                refreshToken,
                "Refresh token cannot be null."
        );

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Tokens cannot be issued for an inactive account."
            );
        }

        List<UserRole> userRoles =
                userRoleRepository.findAllByUser_Id(user.getId());

        if (userRoles.size() != 1) {
            throw new IllegalStateException(
                    "An active account must have exactly one role."
            );
        }

        RoleName role = userRoles.getFirst()
                .getRole()
                .getName();

        AccessTokenService.IssuedAccessToken accessToken =
                accessTokenService.issue(
                        user,
                        List.of(role)
                );

        return new AuthTokensResponse(
                "Bearer",
                accessToken.value(),
                accessToken.expiresInSeconds(),
                accessToken.expiresAt(),
                refreshToken.value(),
                refreshToken.expiresAt(),
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                role
        );
    }
}