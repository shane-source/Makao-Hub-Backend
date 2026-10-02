package com.makaohub.backend.profile.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.profile.api.AvatarUploadUrlResponse;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AvatarUploadService {

    private static final Pattern AVATAR_FILE_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{12}\\.(jpg|png|webp)"
    );

    private final UserRepository userRepository;
    private final SupabaseStorageClient storageClient;
    private final UploadSecurityProperties uploadLimits;

    public AvatarUploadService(
            UserRepository userRepository,
            SupabaseStorageClient storageClient,
            UploadSecurityProperties uploadLimits
    ) {
        this.userRepository = userRepository;
        this.storageClient = storageClient;
        this.uploadLimits = uploadLimits;
    }

    @Transactional(readOnly = true)
    public AvatarUploadUrlResponse createUploadUrl(
            UUID authenticatedUserId,
            String contentType
    ) {
        User user = activeUser(authenticatedUserId);

        String objectPath = user.getId()
                + "/"
                + UUID.randomUUID()
                + extensionFor(contentType);

        SupabaseStorageClient.SignedUploadUrl signedUpload =
                storageClient.createSignedAvatarUploadUrl(objectPath);

        return new AvatarUploadUrlResponse(
                signedUpload.uploadUrl(),
                signedUpload.objectPath(),
                contentType
        );
    }

    @Transactional
    public void confirmUpload(
            UUID authenticatedUserId,
            String objectPath
    ) {
        User user = activeUser(authenticatedUserId);

        if (!isOwnedAvatarPath(user.getId(), objectPath)) {
            throw invalidAvatarUpload();
        }

        SupabaseStorageClient.StoredObjectInfo objectInfo =
                storageClient.getAvatarInfo(objectPath);

        if (objectInfo == null
                || objectInfo.sizeBytes() <= 0
                || objectInfo.sizeBytes() > uploadLimits.avatarMaxBytes()
                || !hasExpectedContentType(
                objectPath,
                objectInfo.contentType()
        )) {
            throw invalidAvatarUpload();
        }

        user.changeAvatarObjectPath(objectPath);
    }

    private User activeUser(UUID authenticatedUserId) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(AvatarUploadService::accountUnavailable);

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw accountUnavailable();
        }

        return user;
    }

    private static boolean isOwnedAvatarPath(
            UUID userId,
            String objectPath
    ) {
        String prefix = userId + "/";

        return objectPath != null
                && objectPath.startsWith(prefix)
                && AVATAR_FILE_NAME.matcher(
                objectPath.substring(prefix.length())
        ).matches();
    }

    private static boolean hasExpectedContentType(
            String objectPath,
            String contentType
    ) {
        return switch (extensionOf(objectPath)) {
            case ".jpg" -> "image/jpeg".equals(contentType);
            case ".png" -> "image/png".equals(contentType);
            case ".webp" -> "image/webp".equals(contentType);
            default -> false;
        };
    }
    private static String extensionOf(String objectPath) {
        int lastDot = objectPath.lastIndexOf(".");
        return lastDot < 0 ? "" : objectPath.substring(lastDot);
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw invalidAvatarUpload();
        };
    }

    private static ResponseStatusException invalidAvatarUpload() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "The avatar upload is invalid or unavailable."
        );
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}