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

class LandlordEvidencePolicyTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final UserRepository users = mock(UserRepository.class);
    private final UserRoleRepository roles = mock(UserRoleRepository.class);
    private final RoleVerificationRepository verification = mock(RoleVerificationRepository.class);
    private final LandlordVerificationDocumentRepository documents = mock(LandlordVerificationDocumentRepository.class);
    private final SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
    private final UploadSecurityProperties limits = mock(UploadSecurityProperties.class);
    private final OwnershipPdfValidator pdf = mock(OwnershipPdfValidator.class);
    private final VerificationImageValidator image = mock(VerificationImageValidator.class);
    private final LandlordVerificationService service = new LandlordVerificationService(users, roles,
            verification, documents, storage, limits, pdf, image);

    LandlordEvidencePolicyTest() {
        var user = mock(User.class);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(users.findById(actor)).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(actor)).thenReturn(Optional.of(user));
        when(roles.existsByUser_IdAndRole_Name(actor, RoleName.LANDLORD)).thenReturn(true);
        when(limits.verificationDocumentMaxBytes()).thenReturn(10_485_760L);
        when(documents.save(any())).thenAnswer(call -> {
            LandlordVerificationDocument record = call.getArgument(0);
            ReflectionTestUtils.setField(record, "id", UUID.randomUUID()); return record;
        });
        when(storage.createSignedEvidenceUploadUrl(anyString())).thenReturn(
                new SupabaseStorageClient.SignedUploadUrl("https://synthetic.supabase.co/storage/v1/object/upload/sign/test", "unused"));
    }

    @Test void ownershipAcceptsOnlyPdfAndUsesNonOverwritingUploadCapability() {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.createDocumentUploadUrl(
                actor, VerificationDocumentType.OWNERSHIP_PROOF, "image/jpeg", false)).getStatusCode().value());
        var ticket = service.createDocumentUploadUrl(actor, VerificationDocumentType.OWNERSHIP_PROOF, "application/pdf", false);
        assertEquals("application/pdf", ticket.contentType());
        verify(storage).createSignedEvidenceUploadUrl(endsWith(".pdf"));
        verify(storage, never()).createSignedVerificationDocumentUploadUrl(any());
        assertThrows(ResponseStatusException.class, () -> service.createDocumentUploadUrl(actor, VerificationDocumentType.ID_FRONT, "application/pdf", false));
        assertThrows(ResponseStatusException.class, () -> service.createDocumentUploadUrl(actor, VerificationDocumentType.RENTAL_AGREEMENT, "application/pdf", false));
    }

    @Test void explicitReplacementKeepsOldRecordAndCannotChangePendingOrApprovedEvidence() {
        var old = document(VerificationDocumentType.OWNERSHIP_PROOF, "application/pdf");
        old.markReady(100);
        when(documents.findByLandlordIdAndDocumentTypeAndStatusIn(eq(actor), eq(VerificationDocumentType.OWNERSHIP_PROOF), anyCollection()))
                .thenReturn(Optional.of(old));
        assertThrows(ResponseStatusException.class, () -> service.createDocumentUploadUrl(actor, old.getDocumentType(), old.getContentType(), false));
        service.createDocumentUploadUrl(actor, old.getDocumentType(), old.getContentType(), true);
        assertEquals(VerificationDocumentStatus.DELETED, old.getStatus());
        verify(documents).saveAndFlush(old);
        for (VerificationStatus status : List.of(VerificationStatus.PENDING, VerificationStatus.VERIFIED)) {
            var review = new RoleVerification(actor, RoleName.LANDLORD);
            ReflectionTestUtils.setField(review, "status", status);
            when(verification.findByUserIdAndRole(actor, RoleName.LANDLORD)).thenReturn(Optional.of(review));
            assertThrows(ResponseStatusException.class, () -> service.createDocumentUploadUrl(actor, old.getDocumentType(), old.getContentType(), true));
        }
    }

    @Test void foreignDocumentNeverReachesStorageOrScanning() {
        assertThrows(ResponseStatusException.class, () -> service.confirmDocumentUpload(actor, id));
        verify(documents).findByIdAndLandlordId(id, actor);
        verifyNoInteractions(storage, pdf, image);
    }

    @Test void scannerFailureLeavesUploadUnconfirmedAndCleanPdfBecomesReady() {
        var record = document(VerificationDocumentType.OWNERSHIP_PROOF, "application/pdf");
        when(documents.findByIdAndLandlordId(id, actor)).thenReturn(Optional.of(record));
        byte[] data = "%PDF-1.7".getBytes();
        when(storage.getVerificationDocumentInfo(record.getObjectPath())).thenReturn(new SupabaseStorageClient.StoredObjectInfo("application/pdf", data.length));
        when(storage.readVerificationDocument(record.getObjectPath(), data.length)).thenAnswer(call -> data.clone());
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(pdf).validate(any());
        assertThrows(ResponseStatusException.class, () -> service.confirmDocumentUpload(actor, id));
        assertEquals(VerificationDocumentStatus.UPLOADING, record.getStatus());
        doNothing().when(pdf).validate(any());
        service.confirmDocumentUpload(actor, id);
        assertEquals(VerificationDocumentStatus.READY, record.getStatus());
        assertThrows(ResponseStatusException.class, () -> service.confirmDocumentUpload(actor, id));
    }

    @Test void imageReadinessRequiresServerNormalizationToSucceed() {
        var record = document(VerificationDocumentType.ID_FRONT, "image/jpeg");
        when(documents.findByIdAndLandlordId(id, actor)).thenReturn(Optional.of(record));
        when(storage.getVerificationDocumentInfo(record.getObjectPath())).thenReturn(new SupabaseStorageClient.StoredObjectInfo("image/jpeg", 3));
        when(storage.readVerificationDocument(record.getObjectPath(), 3)).thenReturn(new byte[] {1,2,3});
        when(image.prepare(any(), eq("image/jpeg"))).thenReturn(new byte[] {4,5});
        service.confirmDocumentUpload(actor, id);
        verify(storage).storePreparedVerificationImage(eq(record.getObjectPath()), eq("image/jpeg"), any());
        assertEquals(2L, record.getSizeBytes());
    }

    @Test void legacyReadyEvidenceCannotBeSubmittedOrReportedAsSecurityChecked() {
        var legacyPdf = new LandlordVerificationDocument(actor, VerificationDocumentType.OWNERSHIP_PROOF,
                actor + "/ownership/legacy.pdf", "application/pdf");
        legacyPdf.markReady(100);
        assertFalse(legacyPdf.hasCurrentSecurityChecks());
        when(documents.findAllByLandlordIdAndStatus(actor, VerificationDocumentStatus.READY))
                .thenReturn(List.of(legacyPdf));

        var failure = assertThrows(ResponseStatusException.class,
                () -> service.submitMyVerification(actor));
        assertEquals(400, failure.getStatusCode().value());
        verify(verification, never()).save(any());
    }

    @Test void legacyUploadingEvidenceCannotBeConfirmedWithoutExplicitReplacement() {
        var legacy = new LandlordVerificationDocument(actor, VerificationDocumentType.ID_FRONT,
                actor + "/id/legacy.jpg", "image/jpeg");
        when(documents.findByIdAndLandlordId(id, actor)).thenReturn(Optional.of(legacy));

        var failure = assertThrows(ResponseStatusException.class,
                () -> service.confirmDocumentUpload(actor, id));
        assertEquals(409, failure.getStatusCode().value());
        verifyNoInteractions(storage, pdf, image);
    }

    private LandlordVerificationDocument document(VerificationDocumentType type, String mime) {
        return new LandlordVerificationDocument(actor, type, actor + "/evidence-v1/synthetic.pdf", mime);
    }
}
