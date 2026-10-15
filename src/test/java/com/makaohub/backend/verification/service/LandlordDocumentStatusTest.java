package com.makaohub.backend.verification.service;

import com.makaohub.backend.auth.domain.*;
import com.makaohub.backend.auth.repository.*;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.domain.*;
import com.makaohub.backend.verification.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LandlordDocumentStatusTest {
    @Test void readinessQueryAlwaysUsesAuthenticatedOwnerAndRequiredTypes() {
        var owner = UUID.randomUUID();
        var users = mock(UserRepository.class);
        var roles = mock(UserRoleRepository.class);
        var documents = mock(LandlordVerificationDocumentRepository.class);
        var user = mock(User.class);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(users.findById(owner)).thenReturn(Optional.of(user));
        when(roles.existsByUser_IdAndRole_Name(owner, RoleName.LANDLORD)).thenReturn(true);
        var document = new LandlordVerificationDocument(owner, VerificationDocumentType.ID_FRONT,
                "private-path-not-for-api", "image/jpeg");
        document.markReady(100);
        when(documents.findAllByLandlordIdAndDocumentTypeInAndStatusIn(eq(owner), anyCollection(), anyCollection()))
                .thenReturn(List.of(document));
        var service = new LandlordVerificationService(users, roles, mock(RoleVerificationRepository.class),
                documents, mock(SupabaseStorageClient.class), mock(UploadSecurityProperties.class), mock(OwnershipPdfValidator.class), mock(VerificationImageValidator.class));
        var result = service.getMyDocuments(owner);
        assertEquals(VerificationDocumentType.ID_FRONT, result.getFirst().documentType());
        assertEquals(VerificationDocumentStatus.READY, result.getFirst().status());
        verify(documents).findAllByLandlordIdAndDocumentTypeInAndStatusIn(eq(owner),
                eq(EnumSet.of(VerificationDocumentType.ID_FRONT, VerificationDocumentType.ID_BACK,
                        VerificationDocumentType.SELFIE, VerificationDocumentType.OWNERSHIP_PROOF)),
                eq(List.of(VerificationDocumentStatus.UPLOADING, VerificationDocumentStatus.READY)));
        assertFalse(result.getFirst().toString().contains("private-path"));
    }
    @Test void inactiveOrWrongRoleCannotQueryDocuments() {
        for (var state : List.of(AccountStatus.ACTIVE, AccountStatus.SUSPENDED)) {
            var owner = UUID.randomUUID();
            var users = mock(UserRepository.class);
            var roles = mock(UserRoleRepository.class);
            var documents = mock(LandlordVerificationDocumentRepository.class);
            var user = mock(User.class);
            when(user.getAccountStatus()).thenReturn(state);
            when(users.findById(owner)).thenReturn(Optional.of(user));
            var service = new LandlordVerificationService(users, roles, mock(RoleVerificationRepository.class),
                    documents, mock(SupabaseStorageClient.class), mock(UploadSecurityProperties.class), mock(OwnershipPdfValidator.class), mock(VerificationImageValidator.class));
            assertThrows(ResponseStatusException.class, () -> service.getMyDocuments(owner));
            verifyNoInteractions(documents);
        }
    }
}
