package com.makaohub.backend.verification.service;

import com.makaohub.backend.auth.domain.*;
import com.makaohub.backend.auth.repository.*;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.domain.*;
import com.makaohub.backend.verification.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RenterEvidencePolicyTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final UserRepository users = mock(UserRepository.class);
    private final UserRoleRepository roles = mock(UserRoleRepository.class);
    private final RoleVerificationRepository verification = mock(RoleVerificationRepository.class);
    private final RenterVerificationDocumentRepository documents = mock(RenterVerificationDocumentRepository.class);
    private final SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
    private final UploadSecurityProperties limits = mock(UploadSecurityProperties.class);
    private final VerificationImageValidator image = mock(VerificationImageValidator.class);
    private final RenterVerificationService service = new RenterVerificationService(users, roles,
            verification, documents, storage, limits, image);

    RenterEvidencePolicyTest() {
        var user = mock(User.class);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(users.findById(actor)).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(actor)).thenReturn(Optional.of(user));
        when(roles.existsByUser_IdAndRole_Name(actor, RoleName.RENTER)).thenReturn(true);
        when(limits.verificationDocumentMaxBytes()).thenReturn(10_485_760L);
        when(documents.save(any())).thenAnswer(call -> {
            RenterVerificationDocument record = call.getArgument(0);
            ReflectionTestUtils.setField(record, "id", UUID.randomUUID()); return record;
        });
        when(storage.createSignedEvidenceUploadUrl(anyString())).thenReturn(
                new SupabaseStorageClient.SignedUploadUrl("https://synthetic.supabase.co/storage/v1/object/upload/sign/test", "unused"));
    }

    @Test void acceptsOnlyIdSidesAndSelfieImages() {
        for (var type : List.of(VerificationDocumentType.OWNERSHIP_PROOF, VerificationDocumentType.RENTAL_AGREEMENT))
            assertThrows(ResponseStatusException.class, () -> service.createDocumentUploadUrl(actor,type,"image/jpeg",false));
        assertThrows(ResponseStatusException.class, () -> service.createDocumentUploadUrl(actor,VerificationDocumentType.ID_FRONT,"application/pdf",false));
        service.createDocumentUploadUrl(actor,VerificationDocumentType.ID_FRONT,"image/jpeg",false);
        verify(storage).createSignedEvidenceUploadUrl(endsWith(".jpg"));
        verify(storage,never()).createSignedVerificationDocumentUploadUrl(any());
    }

    @Test void explicitReplacementKeepsOldRecordAndCannotChangePendingOrApprovedEvidence() {
        var old = document(VerificationDocumentType.ID_FRONT, "image/jpeg");
        old.markReady(100);
        when(documents.findByRenterIdAndDocumentTypeAndStatusIn(eq(actor), eq(VerificationDocumentType.ID_FRONT), anyCollection()))
                .thenReturn(Optional.of(old));
        assertThrows(ResponseStatusException.class, () -> service.createDocumentUploadUrl(actor, old.getDocumentType(), old.getContentType(), false));
        service.createDocumentUploadUrl(actor, old.getDocumentType(), old.getContentType(), true);
        assertEquals(VerificationDocumentStatus.DELETED, old.getStatus());
        verify(documents).saveAndFlush(old);
        for (VerificationStatus status : List.of(VerificationStatus.PENDING, VerificationStatus.VERIFIED)) {
            var review = new RoleVerification(actor, RoleName.RENTER);
            ReflectionTestUtils.setField(review, "status", status);
            when(verification.findByUserIdAndRole(actor, RoleName.RENTER)).thenReturn(Optional.of(review));
            assertThrows(ResponseStatusException.class, () -> service.createDocumentUploadUrl(actor, old.getDocumentType(), old.getContentType(), true));
        }
    }

    @Test void foreignDocumentNeverReachesStorageOrScanning() {
        assertThrows(ResponseStatusException.class, () -> service.confirmDocumentUpload(actor, id));
        verify(documents).findByIdAndRenterId(id, actor);
        verifyNoInteractions(storage, image);
    }

    @Test void scannerFailureLeavesTheImageUnconfirmed() {
        var record=document(VerificationDocumentType.SELFIE,"image/jpeg");
        when(documents.findByIdAndRenterId(id,actor)).thenReturn(Optional.of(record));
        when(storage.getVerificationDocumentInfo(record.getObjectPath())).thenReturn(new SupabaseStorageClient.StoredObjectInfo("image/jpeg",3));
        when(storage.readVerificationDocument(record.getObjectPath(),3)).thenReturn(new byte[]{1,2,3});
        when(image.prepare(any(),eq("image/jpeg"))).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.confirmDocumentUpload(actor,id)).getStatusCode().value());
        assertEquals(VerificationDocumentStatus.UPLOADING,record.getStatus());
        verify(storage,never()).storePreparedVerificationImage(any(),any(),any());
    }

    @Test void imageReadinessRequiresServerNormalizationToSucceed() {
        var record = document(VerificationDocumentType.ID_FRONT, "image/jpeg");
        when(documents.findByIdAndRenterId(id, actor)).thenReturn(Optional.of(record));
        when(storage.getVerificationDocumentInfo(record.getObjectPath())).thenReturn(new SupabaseStorageClient.StoredObjectInfo("image/jpeg", 3));
        when(storage.readVerificationDocument(record.getObjectPath(), 3)).thenReturn(new byte[] {1,2,3});
        when(image.prepare(any(), eq("image/jpeg"))).thenReturn(new byte[] {4,5});
        service.confirmDocumentUpload(actor, id);
        verify(storage).storePreparedVerificationImage(eq(record.getObjectPath()), eq("image/jpeg"), any());
        assertEquals(2L, record.getSizeBytes());
    }

    @Test void legacyReadyEvidenceCannotBeSubmittedOrReportedAsSecurityChecked() {
        var legacyImage = new RenterVerificationDocument(actor, VerificationDocumentType.RENTAL_AGREEMENT,
                actor + "/id/legacy.jpg", "image/jpeg");
        legacyImage.markReady(100);
        assertFalse(legacyImage.hasCurrentSecurityChecks());
        when(documents.findAllByRenterIdAndStatus(actor, VerificationDocumentStatus.READY))
                .thenReturn(List.of(legacyImage));

        var failure = assertThrows(ResponseStatusException.class,
                () -> service.submitMyVerification(actor));
        assertEquals(400, failure.getStatusCode().value());
        verify(verification, never()).save(any());
    }

    @Test void legacyUploadingEvidenceCannotBeConfirmedWithoutExplicitReplacement() {
        var legacy = new RenterVerificationDocument(actor, VerificationDocumentType.ID_FRONT,
                actor + "/id/legacy.jpg", "image/jpeg");
        when(documents.findByIdAndRenterId(id, actor)).thenReturn(Optional.of(legacy));

        var failure = assertThrows(ResponseStatusException.class,
                () -> service.confirmDocumentUpload(actor, id));
        assertEquals(409, failure.getStatusCode().value());
        verifyNoInteractions(storage, image);
    }

    private RenterVerificationDocument document(VerificationDocumentType type, String mime) {
        return new RenterVerificationDocument(actor, type, actor + "/renter-evidence-v1/synthetic.jpg", mime);
    }
}
