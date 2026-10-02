package com.makaohub.backend.profile.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.profile.service.AvatarUploadService;
import com.makaohub.backend.profile.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;
    private final AvatarUploadService avatarUploadService;

    public ProfileController(
            ProfileService profileService,
            AvatarUploadService avatarUploadService
    ) {
        this.profileService = profileService;
        this.avatarUploadService = avatarUploadService;
    }

    @GetMapping("/me")
    public MyProfileResponse getMyProfile(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return profileService.getMyProfile(extractUserId(jwt));
    }

    @PatchMapping("/me/name")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateFullName(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateFullNameRequest request
    ) {
        profileService.updateFullName(
                extractUserId(jwt),
                request.fullName()
        );
    }

    @PostMapping("/me/avatar/upload-url")
    public AvatarUploadUrlResponse createAvatarUploadUrl(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AvatarUploadUrlRequest request
    ) {
        return avatarUploadService.createUploadUrl(
                extractUserId(jwt),
                request.contentType()
        );
    }

    @PostMapping("/me/avatar/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmAvatarUpload(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConfirmAvatarUploadRequest request
    ) {
        avatarUploadService.confirmUpload(
                extractUserId(jwt),
                request.objectPath()
        );
    }

    private UUID extractUserId(Jwt jwt) {
        String subject = jwt.getSubject();

        if (subject == null) {
            throw accountUnavailable();
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw accountUnavailable();
        }
    }

    private AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}