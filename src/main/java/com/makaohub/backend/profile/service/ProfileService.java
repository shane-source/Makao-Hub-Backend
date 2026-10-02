package com.makaohub.backend.profile.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.auth.service.PhoneProtectionService;
import com.makaohub.backend.profile.api.MyProfileResponse;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PhoneProtectionService phoneProtectionService;
    private final SupabaseStorageClient storageClient;

    public ProfileService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            PhoneProtectionService phoneProtectionService,
            SupabaseStorageClient storageClient
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.phoneProtectionService = phoneProtectionService;
        this.storageClient = storageClient;
    }

    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile(
            UUID authenticatedUserId
    ) {
        Objects.requireNonNull(
                authenticatedUserId,
                "Authenticated user ID cannot be null."
        );

        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(ProfileService::accountUnavailable);

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

        String phoneNumber = phoneProtectionService.decrypt(
                user.getPhoneCiphertext()
        );

        String avatarUrl = user.getAvatarObjectPath() == null
                ? null
                : storageClient.createSignedAvatarReadUrl(
                user.getAvatarObjectPath()
        );

        return new MyProfileResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                phoneNumber,
                role,
                user.getAccountStatus(),
                user.getCreatedAt(),
                avatarUrl
        );
    }

    @Transactional
    public void updateFullName(
            UUID authenticatedUserId,
            String fullName
    ) {
        Objects.requireNonNull(
                authenticatedUserId,
                "Authenticated user ID cannot be null."
        );

        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(ProfileService::accountUnavailable);

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw accountUnavailable();
        }

        user.changeFullName(fullName);
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}