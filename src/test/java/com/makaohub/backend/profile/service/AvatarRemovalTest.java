package com.makaohub.backend.profile.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.service.VerificationImageValidator;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarRemovalTest {
    private final UserRepository users = mock(UserRepository.class);
    private final SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
    private final AvatarUploadService service = new AvatarUploadService(users, storage,
            mock(UploadSecurityProperties.class), mock(VerificationImageValidator.class));

    @Test void removesOnlyTheOwnersPhotoAndAllowsRepeatedRemoval() {
        UUID id = UUID.randomUUID();
        String path = id + "/" + UUID.randomUUID() + ".jpg";
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(user.getAvatarObjectPath()).thenReturn(path, null);
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(user));
        service.removeAvatar(id);
        verify(storage).deleteAvatar(path);
        verify(user).removeAvatar();
        service.removeAvatar(id);
        verifyNoMoreInteractions(storage);
    }

    @Test void storageFailureDoesNotClearTheProfile() {
        UUID id = UUID.randomUUID();
        String path = id + "/" + UUID.randomUUID() + ".jpg";
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(user.getAvatarObjectPath()).thenReturn(path);
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(user));
        doThrow(new IllegalStateException("storage unavailable")).when(storage).deleteAvatar(path);
        assertThrows(IllegalStateException.class, () -> service.removeAvatar(id));
        verify(user, never()).removeAvatar();
    }

    @Test void refusesAnotherUsersPhoto() {
        UUID id = UUID.randomUUID();
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(user.getAvatarObjectPath()).thenReturn(UUID.randomUUID() + "/" + UUID.randomUUID() + ".jpg");
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(user));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.removeAvatar(id));
        verifyNoInteractions(storage);
    }
}
