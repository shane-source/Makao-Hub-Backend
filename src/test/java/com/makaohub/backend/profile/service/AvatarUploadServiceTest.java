package com.makaohub.backend.profile.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.domain.VerificationDocumentType;
import com.makaohub.backend.verification.service.VerificationImageValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvatarUploadServiceTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PATH = USER + "/00000000-0000-0000-0000-000000000002.jpg";
    @Mock UserRepository users;
    @Mock User user;
    @Mock SupabaseStorageClient storage;
    @Mock VerificationImageValidator images;
    AvatarUploadService service;

    @BeforeEach void setup() {
        service = new AvatarUploadService(users, storage, new UploadSecurityProperties(5_242_880, 10_485_760, 10, 10, 10, 10, 10), images);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
    }

    @Test void confirmsOnlyAfterImageValidationAndCleanStorage() {
        when(user.getId()).thenReturn(USER);
        when(storage.getAvatarInfo(PATH)).thenReturn(new SupabaseStorageClient.StoredObjectInfo("image/jpeg", 3));
        when(storage.readAvatar(PATH, 3)).thenReturn(new byte[]{1, 2, 3});
        when(images.prepare(any(), eq("image/jpeg"), eq(VerificationDocumentType.SELFIE))).thenReturn(new byte[]{4, 5});
        service.confirmUpload(USER, PATH);
        var order = inOrder(images, storage, user);
        order.verify(images).prepare(any(), eq("image/jpeg"), eq(VerificationDocumentType.SELFIE));
        var path = org.mockito.ArgumentCaptor.forClass(String.class);
        order.verify(storage).storePreparedAvatar(path.capture(), eq("image/jpeg"), any());
        order.verify(storage).deleteUnconfirmedAvatar(PATH);
        order.verify(user).changeAvatarObjectPath(path.getValue());
        assertTrue(path.getValue().startsWith(USER + "/"));
        assertNotEquals(PATH, path.getValue());
    }

    @Test void rejectsAnotherUsersPathBeforeStorageAccess() {
        when(user.getId()).thenReturn(USER);
        assertThrows(ResponseStatusException.class, () -> service.confirmUpload(USER, "00000000-0000-0000-0000-000000000009/00000000-0000-0000-0000-000000000002.jpg"));
        verifyNoInteractions(storage, images);
        verify(user, never()).changeAvatarObjectPath(any());
    }

    @Test void rejectedImageNeverBecomesProfilePhoto() {
        when(user.getId()).thenReturn(USER);
        when(storage.getAvatarInfo(PATH)).thenReturn(new SupabaseStorageClient.StoredObjectInfo("image/jpeg", 3));
        when(storage.readAvatar(PATH, 3)).thenReturn(new byte[]{1, 2, 3});
        when(images.prepare(any(), eq("image/jpeg"), eq(VerificationDocumentType.SELFIE))).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        assertThrows(ResponseStatusException.class, () -> service.confirmUpload(USER, PATH));
        verify(storage, never()).storePreparedAvatar(any(), any(), any());
        verify(user, never()).changeAvatarObjectPath(any());
    }
}
