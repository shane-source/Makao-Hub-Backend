package com.makaohub.backend.verification.service;

import com.makaohub.backend.verification.domain.VerificationDocumentType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerificationImageValidatorTest {
    private final ClamAvScanner scanner = mock(ClamAvScanner.class);
    private final KenyanNationalIdValidator nationalId = mock(KenyanNationalIdValidator.class);
    private final VerificationImageValidator validator = new VerificationImageValidator(scanner, nationalId);

    @Test void imagesAreScannedDecodedAndReencodedWithoutTrustingDeclaredMime() throws Exception {
        for (String format : new String[] {"JPEG", "PNG"}) {
            byte[] bytes = image(format);
            byte[] prepared = validator.prepare(bytes, format.equals("JPEG") ? "image/jpeg" : "image/png", VerificationDocumentType.SELFIE);
            verify(scanner).assertClean(bytes);
            var actual = ImageIO.read(new ByteArrayInputStream(prepared));
            assertEquals(64, actual.getWidth()); assertEquals(32, actual.getHeight());
        }
        assertThrows(ResponseStatusException.class, () -> validator.prepare(image("PNG"), "image/jpeg", VerificationDocumentType.SELFIE));
        verifyNoInteractions(nationalId);
    }

    @Test void malformedAndScannerFailureNeverProducePreparedEvidence() throws Exception {
        assertThrows(ResponseStatusException.class, () -> validator.prepare(new byte[] {1,2,3}, "image/jpeg", VerificationDocumentType.SELFIE));
        byte[] bytes = image("JPEG");
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(scanner).assertClean(bytes);
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> validator.prepare(bytes, "image/jpeg", VerificationDocumentType.SELFIE)).getStatusCode().value());
    }

    @Test void sourceMetadataIsNotCopiedIntoPreparedJpeg() throws Exception {
        byte[] original = image("JPEG");
        byte[] marker = "GPS-PRIVATE-SYNTHETIC".getBytes(StandardCharsets.ISO_8859_1);
        byte[] withApplicationSegment = new byte[original.length + marker.length + 4];
        System.arraycopy(original, 0, withApplicationSegment, 0, 2);
        withApplicationSegment[2] = (byte) 0xFF;
        withApplicationSegment[3] = (byte) 0xE1;
        int segmentLength = marker.length + 2;
        withApplicationSegment[4] = (byte) (segmentLength >>> 8);
        withApplicationSegment[5] = (byte) segmentLength;
        System.arraycopy(marker, 0, withApplicationSegment, 6, marker.length);
        System.arraycopy(original, 2, withApplicationSegment, marker.length + 6, original.length - 2);

        byte[] prepared = validator.prepare(withApplicationSegment, "image/jpeg", VerificationDocumentType.SELFIE);
        assertFalse(new String(prepared, StandardCharsets.ISO_8859_1).contains("GPS-PRIVATE-SYNTHETIC"));
        assertNotNull(ImageIO.read(new ByteArrayInputStream(prepared)));
    }

    @Test void bothIdSidesRequireSuccessfulOcrBeforePreparedEvidence() throws Exception {
        for (var side : new VerificationDocumentType[] {VerificationDocumentType.ID_FRONT, VerificationDocumentType.ID_BACK}) {
            byte[] prepared = validator.prepare(image("JPEG"), "image/jpeg", side);
            verify(nationalId).validate(prepared, side);
        }
        for (var status : new HttpStatus[] {
                HttpStatus.BAD_REQUEST, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.INTERNAL_SERVER_ERROR}) {
            reset(nationalId);
            doThrow(new ResponseStatusException(status)).when(nationalId).validate(any(), any());
            assertEquals(status.value(), assertThrows(ResponseStatusException.class,
                    () -> validator.prepare(image("JPEG"), "image/jpeg", VerificationDocumentType.ID_FRONT))
                    .getStatusCode().value());
        }
        reset(nationalId);
        assertThrows(ResponseStatusException.class,
                () -> validator.prepare(new byte[]{1, 2}, "image/jpeg", VerificationDocumentType.ID_FRONT));
        verifyNoInteractions(nationalId);
    }

    private byte[] image(String format) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }
}
