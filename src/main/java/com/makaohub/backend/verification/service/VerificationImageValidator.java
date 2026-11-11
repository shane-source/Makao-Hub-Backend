package com.makaohub.backend.verification.service;

import com.makaohub.backend.verification.domain.VerificationDocumentType;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Independent server decoding/re-encoding and ID OCR; no malware scan is performed. */
@Service
public class VerificationImageValidator {
    private final KenyanNationalIdValidator nationalId;
    public VerificationImageValidator(KenyanNationalIdValidator nationalId) {
        this.nationalId = nationalId;
    }

    public byte[] prepare(byte[] bytes, String contentType, VerificationDocumentType documentType) {
        if (documentType != VerificationDocumentType.ID_FRONT && documentType != VerificationDocumentType.ID_BACK
                && documentType != VerificationDocumentType.SELFIE) throw invalid();
        if (bytes == null || bytes.length == 0 || bytes.length > 10_485_760) throw invalid();
        String format = switch (contentType) {
            case "image/jpeg" -> "JPEG";
            case "image/png" -> "PNG";
            default -> throw invalid();
        };
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                if (!format.equalsIgnoreCase(reader.getFormatName())) throw invalid();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 25_000_000) throw invalid();
                var readOptions = reader.getDefaultReadParam();
                int sample = 1;
                while (Math.max(width, height) / sample > 2400) sample *= 2;
                readOptions.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage decoded = reader.read(0, readOptions);
                if (decoded == null) throw invalid();
                var normalized = new BufferedImage(decoded.getWidth(), decoded.getHeight(),
                        "JPEG".equals(format) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
                try {
                    var graphics = normalized.createGraphics();
                    try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); }
                    var output = new ByteArrayOutputStream();
                    var writer = ImageIO.getImageWritersByFormatName(format).next();
                    try (var imageOutput = new MemoryCacheImageOutputStream(output)) {
                        writer.setOutput(imageOutput);
                        var options = writer.getDefaultWriteParam();
                        if ("JPEG".equals(format)) {
                            options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                            options.setCompressionQuality(0.9f);
                        }
                        writer.write(null, new javax.imageio.IIOImage(normalized, null, null), options);
                        imageOutput.flush();
                    } finally { writer.dispose(); }
                    if (output.size() == 0 || output.size() > 10_485_760) throw invalid();
                    byte[] prepared = output.toByteArray(); // No source metadata is passed to the writer.
                    try {
                        if (documentType != VerificationDocumentType.SELFIE)
                            nationalId.validate(prepared, documentType);
                        return prepared;
                    } catch (RuntimeException failure) {
                        java.util.Arrays.fill(prepared, (byte) 0);
                        throw failure;
                    }
                } finally { decoded.flush(); normalized.flush(); }
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException failure) { throw invalid(); }
    }

    /** Read only the owned, confirmed images for submission; never persist or return OCR names. */
    public void assertMatchingStoredIdNames(SupabaseStorageClient storage, String frontPath, Long frontSize,
            String backPath, Long backSize) {
        if (frontSize == null || backSize == null) throw invalid();
        byte[] front = null, back = null;
        try {
            front = storage.readVerificationDocument(frontPath, frontSize);
            back = storage.readVerificationDocument(backPath, backSize);
            if (front == null || back == null || front.length != frontSize || back.length != backSize) throw invalid();
            nationalId.assertMatchingNames(front, back);
        } finally {
            if (front != null) java.util.Arrays.fill(front, (byte) 0);
            if (back != null) java.util.Arrays.fill(back, (byte) 0);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Choose a readable JPEG or PNG image of at most 10 MB and 25 megapixels.");
    }
}
