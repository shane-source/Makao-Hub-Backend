package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Returns only the applicant's name, profile photo and renter verification state to an authorised owner. */
@Service
public class ApplicationApplicantDetails {
    private final UserRepository users;
    private final RoleVerificationRepository verifications;
    private final SupabaseStorageClient storage;

    public ApplicationApplicantDetails(UserRepository users, RoleVerificationRepository verifications, SupabaseStorageClient storage) {
        this.users = users; this.verifications = verifications; this.storage = storage;
    }

    public Details forApplicant(UUID renterId) {
        var renter = users.findById(renterId).orElse(null);
        if (renter == null) return new Details(null, null, null);
        String status = verifications.findByUserIdAndRole(renterId, RoleName.RENTER)
                .map(value -> value.getStatus().name()).orElse(null);
        String avatar = renter.getAvatarObjectPath() == null ? null : storage.createSignedAvatarReadUrl(renter.getAvatarObjectPath());
        return new Details(renter.getFullName(), avatar, status);
    }

    /** Deliberately not a record: a signed photo URL must not appear in generated toString output. */
    public static final class Details {
        public final String name, avatarUrl, verificationStatus;
        public Details(String name, String avatarUrl, String verificationStatus) {
            this.name = name; this.avatarUrl = avatarUrl; this.verificationStatus = verificationStatus;
        }
    }
}
