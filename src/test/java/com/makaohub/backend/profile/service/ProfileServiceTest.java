package com.makaohub.backend.profile.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.Role;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.auth.service.PhoneProtectionService;
import com.makaohub.backend.profile.api.MyProfileResponse;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "6b93d17b-0662-4718-a17d-736d85093e75"
            );

    private static final Instant CREATED_AT =
            Instant.parse("2026-09-28T08:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private PhoneProtectionService phoneProtectionService;

    @Mock
    private SupabaseStorageClient storageClient;

    @Mock
    private User user;

    @Mock
    private UserRole userRole;

    @Mock
    private Role role;

    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(
                userRepository,
                userRoleRepository,
                phoneProtectionService,
                storageClient
        );
    }

    @Test
    void returnsAuthenticatedUsersProfile() {
        byte[] encryptedPhone = {1, 2, 3};

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user));

        when(user.getAccountStatus())
                .thenReturn(AccountStatus.ACTIVE);

        when(userRoleRepository.findAllByUser_Id(USER_ID))
                .thenReturn(List.of(userRole));

        when(userRole.getRole()).thenReturn(role);
        when(role.getName()).thenReturn(RoleName.LANDLORD);

        when(user.getId()).thenReturn(USER_ID);
        when(user.getFullName()).thenReturn("Wangari Maathai");
        when(user.getEmail())
                .thenReturn("wangari@example.com");
        when(user.getPhoneCiphertext())
                .thenReturn(encryptedPhone);
        when(user.getCreatedAt()).thenReturn(CREATED_AT);
        when(user.getAvatarObjectPath()).thenReturn(null);

        when(phoneProtectionService.decrypt(encryptedPhone))
                .thenReturn("+254712345678");

        MyProfileResponse response =
                profileService.getMyProfile(USER_ID);

        assertEquals(USER_ID, response.id());
        assertEquals("Wangari Maathai", response.fullName());
        assertEquals("wangari@example.com", response.email());
        assertEquals("+254712345678", response.phoneNumber());
        assertEquals(RoleName.LANDLORD, response.role());
        assertEquals(AccountStatus.ACTIVE, response.accountStatus());
        assertEquals(CREATED_AT, response.createdAt());
        assertEquals(null, response.avatarUrl());
    }

    @Test
    void rejectsInactiveAccount() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user));

        when(user.getAccountStatus())
                .thenReturn(AccountStatus.SUSPENDED);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> profileService.getMyProfile(USER_ID)
        );

        assertEquals(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                exception.getReason()
        );

        verifyNoInteractions(
                userRoleRepository,
                phoneProtectionService,
                storageClient
        );
    }

    @Test
    void rejectsAccountWithoutExactlyOneRole() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user));

        when(user.getAccountStatus())
                .thenReturn(AccountStatus.ACTIVE);

        when(userRoleRepository.findAllByUser_Id(USER_ID))
                .thenReturn(List.of());

        AuthException exception = assertThrows(
                AuthException.class,
                () -> profileService.getMyProfile(USER_ID)
        );

        assertEquals(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                exception.getReason()
        );

        verifyNoInteractions(
                phoneProtectionService,
                storageClient
        );
    }
}