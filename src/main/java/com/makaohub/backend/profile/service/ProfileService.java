package com.makaohub.backend.profile.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.auth.service.PhoneProtectionService;
import com.makaohub.backend.auth.service.KenyanPhoneNumberNormalizer;
import com.makaohub.backend.auth.exception.RegistrationException;
import com.makaohub.backend.profile.api.MyProfileResponse;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.domain.RoleVerification;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PhoneProtectionService phoneProtectionService;
    private final SupabaseStorageClient storageClient;
    private final RoleVerificationRepository verificationRepository;

    public ProfileService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            PhoneProtectionService phoneProtectionService,
            SupabaseStorageClient storageClient,
            RoleVerificationRepository verificationRepository
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.phoneProtectionService = phoneProtectionService;
        this.storageClient = storageClient;
        this.verificationRepository = verificationRepository;
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

        byte[] encryptedPhone = user.getPhoneCiphertext();
        String phoneNumber = encryptedPhone == null ? null : phoneProtectionService.decrypt(encryptedPhone);

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
                avatarUrl,
                verificationRepository.findByUserIdAndRole(authenticatedUserId, role)
                        .map(RoleVerification::getStatus)
                        .orElse(VerificationStatus.NOT_STARTED)
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

    @Transactional
    public void completeMissingPhone(UUID authenticatedUserId, String phoneNumber) {
        User user = userRepository.findByIdForUpdate(Objects.requireNonNull(authenticatedUserId))
                .orElseThrow(ProfileService::accountUnavailable);
        if (user.getAccountStatus() != AccountStatus.ACTIVE) throw accountUnavailable();
        List<UserRole> roles = userRoleRepository.findAllByUser_Id(authenticatedUserId);
        if (roles.size() != 1 || !List.of(RoleName.RENTER, RoleName.LANDLORD, RoleName.SUBLESSOR)
                .contains(roles.getFirst().getRole().getName())) throw accountUnavailable();
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new RegistrationException(RegistrationException.Reason.INVALID_PHONE_NUMBER,
                    "Enter your Kenyan mobile number.");
        }
        var protectedPhone = phoneProtectionService.protect(KenyanPhoneNumberNormalizer.normalize(phoneNumber));
        if (user.getPhoneLookupHash() != null) {
            if (user.getPhoneLookupHash().equals(protectedPhone.lookupHash())) return; // Safe retry after a lost response.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone is already configured. Contact support to change it.");
        }
        if (userRepository.existsByPhoneLookupHash(protectedPhone.lookupHash())) {
            throw new RegistrationException(RegistrationException.Reason.PHONE_ALREADY_REGISTERED,
                    "An account already exists for this phone number.");
        }
        user.completeMissingPhone(protectedPhone.ciphertext(), protectedPhone.lookupHash());
        userRepository.flush(); // The existing unique constraint also arbitrates cross-account races.
    }
}
