package com.makaohub.backend.verification.service;

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

/** Independent server decoding/re-encoding; never trust client MIME or metadata stripping. */
@Service
public class VerificationImageValidator {
    private final ClamAvScanner scanner;
    public VerificationImageValidator(ClamAvScanner scanner) { this.scanner = scanner; }

    public byte[] prepare(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0 || bytes.length > 10_485_760) throw invalid();
        String format = switch (contentType) {
            case "image/jpeg" -> "JPEG";
            case "image/png" -> "PNG";
            default -> throw invalid();
        };
        scanner.assertClean(bytes);
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
                    return output.toByteArray(); // No source metadata is passed to the writer.
                } finally { decoded.flush(); normalized.flush(); }
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException failure) { throw invalid(); }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Choose a readable JPEG or PNG image of at most 10 MB and 25 megapixels.");
    }
}
