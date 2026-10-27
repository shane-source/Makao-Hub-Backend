package com.makaohub.backend.verification.service;

import com.makaohub.backend.verification.domain.VerificationDocumentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.ConvolveOp;
import java.awt.image.BufferedImage;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Content screening only; never an authenticity, face-match or government-registry decision. */
@Service
public class KenyanNationalIdValidator {
    private static final int MAX_OCR_BYTES = 262_144;
    private static final double MIN_WORD_CONFIDENCE = 35;
    private static final Pattern NUMBER = Pattern.compile("\\b[0-9]+\\b");
    private final String executable;
    private final long timeoutMillis;
    private final Semaphore capacity = new Semaphore(2);

    @Autowired
    public KenyanNationalIdValidator(
            @Value("${security.verification.tesseract-path:/usr/bin/tesseract}") String executable) {
        this(executable, 20_000);
    }

    KenyanNationalIdValidator(String executable, long timeoutMillis) {
        this.executable = executable;
        this.timeoutMillis = timeoutMillis;
    }

    public void validate(byte[] preparedImage, VerificationDocumentType type) {
        if (type != VerificationDocumentType.ID_FRONT && type != VerificationDocumentType.ID_BACK)
            throw new IllegalArgumentException("OCR requires an ID side");
        if (preparedImage == null || preparedImage.length == 0 || preparedImage.length > 10_485_760)
            throw invalid();
        try {
            validateCurrentOrientation(preparedImage, type);
            return;
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode() != HttpStatus.BAD_REQUEST) throw failure;
        }
        if (type == VerificationDocumentType.ID_FRONT && matchesEnhancedFront(preparedImage, type)) return;
        if (matchesRotatedPortrait(preparedImage, type)) return;
        throw invalid();
    }

    private void validateCurrentOrientation(byte[] preparedImage, VerificationDocumentType type) {
        String sparseText = readText(preparedImage, 11);
        try {
            validateText(sparseText, type);
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode() != HttpStatus.BAD_REQUEST) throw failure;
            // Phone photos often contain a compact card inside a larger frame. Sparse-text mode
            // handles isolated labels; dense-block mode is a bounded fallback for smaller text.
            validateText(sparseText + " " + readText(preparedImage, 6), type);
        }
    }

    /** Phone galleries sometimes store a sideways card without useful EXIF orientation. */
    private boolean matchesRotatedPortrait(byte[] preparedImage, VerificationDocumentType type) {
        BufferedImage source;
        try { source = ImageIO.read(new ByteArrayInputStream(preparedImage)); }
        catch (IOException failure) { return false; }
        if (source == null || source.getHeight() <= source.getWidth()) {
            if (source != null) source.flush();
            return false;
        }
        try {
            for (boolean clockwise : new boolean[] {false, true}) {
                byte[] rotated = rotate(source, clockwise);
                try {
                    validateText(readText(rotated, 11), type);
                    return true;
                } catch (ResponseStatusException failure) {
                    if (failure.getStatusCode() != HttpStatus.BAD_REQUEST) throw failure;
                } finally { java.util.Arrays.fill(rotated, (byte) 0); }
            }
            return false;
        } finally { source.flush(); }
    }

    /** Recover small, low-contrast printed labels without weakening the required field policy. */
    private boolean matchesEnhancedFront(byte[] preparedImage, VerificationDocumentType type) {
        byte[] enhanced = enhanceForOcr(preparedImage);
        if (enhanced == null) return false;
        try {
            validateCurrentOrientation(enhanced, type);
            return true;
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode() != HttpStatus.BAD_REQUEST) throw failure;
            return false;
        } finally { java.util.Arrays.fill(enhanced, (byte) 0); }
    }

    private static byte[] enhanceForOcr(byte[] image) {
        BufferedImage source;
        try { source = ImageIO.read(new ByteArrayInputStream(image)); }
        catch (IOException failure) { return null; }
        if (source == null) return null;
        int longest = Math.max(source.getWidth(), source.getHeight());
        double scale = Math.min(2d, 2400d / longest);
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        var grayscale = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        var sharpened = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        try {
            var graphics = grayscale.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally { graphics.dispose(); }
            new ConvolveOp(new Kernel(3, 3, new float[] {
                    -0.0625f, -0.125f, -0.0625f,
                    -0.125f, 1.75f, -0.125f,
                    -0.0625f, -0.125f, -0.0625f
            }), ConvolveOp.EDGE_NO_OP, null).filter(grayscale, sharpened);
            stretchContrast(sharpened);
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(sharpened, "PNG", output)) return null;
            return output.toByteArray();
        } catch (IOException | RuntimeException failure) {
            return null;
        } finally {
            source.flush();
            grayscale.flush();
            sharpened.flush();
        }
    }

    private static void stretchContrast(BufferedImage image) {
        int[] histogram = new int[256];
        var raster = image.getRaster();
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) histogram[raster.getSample(x, y, 0)]++;
        int trim = Math.max(1, image.getWidth() * image.getHeight() / 100);
        int low = 0, high = 255, count = 0;
        while (low < 255 && count + histogram[low] <= trim) count += histogram[low++];
        count = 0;
        while (high > 0 && count + histogram[high] <= trim) count += histogram[high--];
        if (high <= low) return;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int value = raster.getSample(x, y, 0);
                raster.setSample(x, y, 0, Math.max(0, Math.min(255, (value - low) * 255 / (high - low))));
            }
        }
    }

    private static byte[] rotate(BufferedImage source, boolean clockwise) {
        var rotated = new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_INT_RGB);
        var graphics = rotated.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rotated.getWidth(), rotated.getHeight());
            if (clockwise) {
                graphics.translate(source.getHeight(), 0);
                graphics.rotate(Math.PI / 2);
            } else {
                graphics.translate(0, source.getWidth());
                graphics.rotate(-Math.PI / 2);
            }
            graphics.drawImage(source, 0, 0, null);
        } finally { graphics.dispose(); }
        try {
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(rotated, "PNG", output)) throw unavailable();
            return output.toByteArray();
        } catch (IOException failure) { throw unavailable(); }
        finally { rotated.flush(); }
    }

    private String readText(byte[] image, int pageSegmentationMode) {
        try {
            if (executable == null || executable.isBlank() || !Path.of(executable).isAbsolute()) throw unavailable();
        } catch (InvalidPathException failure) { throw unavailable(); }
        if (!capacity.tryAcquire()) throw unavailable();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var builder = new ProcessBuilder(executable, "stdin", "stdout", "-l", "eng",
                    "--oem", "1", "--psm", Integer.toString(pageSegmentationMode), "tsv");
            builder.redirectError(ProcessBuilder.Redirect.DISCARD); // Native diagnostics may contain PII.
            builder.environment().put("OMP_THREAD_LIMIT", "1");
            Process process = builder.start(); // Trusted executable, fixed arguments, no shell or document path.
            try {
                Future<String> result = executor.submit(() -> {
                    Future<?> input = executor.submit(() -> {
                        try (var pipe = process.getOutputStream()) { pipe.write(image); }
                        return null;
                    });
                    byte[] output;
                    try (var pipe = process.getInputStream()) {
                        output = pipe.readNBytes(MAX_OCR_BYTES + 1);
                    }
                    try {
                        if (output.length > MAX_OCR_BYTES) throw new IOException("OCR output limit");
                        input.get();
                        if (process.waitFor() != 0) throw new IOException("OCR failed");
                        return confidentText(new String(output, StandardCharsets.UTF_8));
                    } finally { java.util.Arrays.fill(output, (byte) 0); }
                });
                return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } finally {
                // Kill/close before joining workers, including when a pipe blocks or the deadline expires.
                process.destroyForcibly();
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | ExecutionException | TimeoutException | IllegalArgumentException failure) {
            throw unavailable(); // Missing engine/language, crash, overflow and timeout never accept an ID.
        } finally { capacity.release(); }
    }

    static String confidentText(String tsv) throws IOException {
        if (!tsv.startsWith("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext"))
            throw new IOException("Invalid OCR response");
        var text = new StringBuilder();
        for (String row : tsv.split("\\R")) {
            String[] fields = row.split("\t", 12);
            if (fields.length != 12 || !"5".equals(fields[0])) continue;
            try {
                double confidence = Double.parseDouble(fields[10]);
                // RECOMMENDED screening threshold, not an identity-confidence score.
                if (Double.isFinite(confidence) && confidence >= MIN_WORD_CONFIDENCE && confidence <= 100)
                    text.append(fields[11]).append(' ');
            } catch (NumberFormatException failure) { throw new IOException("Invalid OCR confidence"); }
        }
        return text.toString();
    }

    static void validateText(String recognized, VerificationDocumentType type) {
        String text = recognized.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9<]+", " ").trim();
        // ponytail: printed-field screening, NOT forgery detection. The final identity decision stays
        // with an authorised reviewer; this gate only rejects unrelated or unreadable uploads.
        boolean kenya = has(text, "REPUBLIC OF KENYA") || has(text, "JAMHURI YA KENYA")
                || has(text, "NATIONALITY KENYAN");
        boolean legacyIdLabel = hasApproximateAny(text, "ID NUMBER", "ID NO");
        boolean birthDate = hasApproximate(text, "DATE OF BIRTH");
        boolean issueDate = hasApproximate(text, "DATE OF ISSUE");
        int distinctDateFields = birthDate || issueDate ? 1 : 0;
        if (birthDate && issueDate && hasApproximate(text, "BIRTH") && hasApproximate(text, "ISSUE"))
            distinctDateFields++;
        int legacyFrontFields = (hasApproximateAny(text, "FULL NAMES", "FULL NAME") ? 1 : 0)
                + distinctDateFields
                + (hasApproximateAny(text, "SEX", "GENDER") ? 1 : 0)
                + (hasApproximate(text, "SERIAL NUMBER") ? 1 : 0)
                + (hasAny(text, "DISTRICT", "DISTRICT OF BIRTH") ? 1 : 0);
        boolean legacyFront = legacyIdLabel && legacyFrontFields >= 3;
        boolean maishaFront = hasAny(text, "MAISHA NUMBER", "MAISHA NAMBA", "MAISHA CARD")
                && hasAny(text, "FULL NAMES", "FULL NAME", "SURNAME")
                && hasAny(text, "GIVEN NAME", "GIVEN NAMES")
                && has(text, "NATIONALITY")
                && hasAny(text, "DATE OF EXPIRY", "EXPIRY DATE");
        boolean front = kenya && NUMBER.matcher(text).find() && (legacyFront || maishaFront);

        boolean legacyBack = hasAny(text, "DISTRICT", "DISTRICT OF BIRTH")
                && has(text, "DIVISION")
                && has(text.replace("SUB LOCATION", ""), "LOCATION")
                && has(text, "SUB LOCATION")
                && hasAny(text, "PLACE OF ISSUE", "DATE OF ISSUE", "PRINCIPAL REGISTRAR");
        boolean machineReadableBack = (text.contains("IDKYA") || text.contains("IDKEN"))
                && text.chars().filter(character -> character == '<').count() >= 4;
        boolean back = legacyBack || machineReadableBack;
        if (type == VerificationDocumentType.ID_FRONT ? !front : !back) throw invalid();
    }

    private static boolean hasAny(String text, String... phrases) {
        for (String phrase : phrases) if (has(text, phrase)) return true;
        return false;
    }

    private static boolean hasApproximateAny(String text, String... phrases) {
        for (String phrase : phrases) if (hasApproximate(text, phrase)) return true;
        return false;
    }

    /** Bounded OCR-error tolerance for known labels only; never applied to identity values. */
    private static boolean hasApproximate(String text, String phrase) {
        if (has(text, phrase)) return true;
        String[] words = text.split(" +"), target = phrase.split(" ");
        for (int width = Math.max(1, target.length - 1); width <= target.length + 1; width++) {
            for (int start = 0; start + width <= words.length; start++) {
                String candidate = String.join(" ", java.util.Arrays.copyOfRange(words, start, start + width));
                int longest = Math.max(candidate.length(), phrase.length());
                if (editDistance(candidate, phrase) * 20 <= longest * 9) return true;
            }
        }
        return false;
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1], current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) previous[column] = column;
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++)
                current[column] = Math.min(Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1));
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[right.length()];
    }

    private static boolean has(String text, String phrase) {
        return (" " + text + " ").contains(" " + phrase + " ");
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The image could not be recognized as the requested side of a Kenyan national ID. "
                        + "Upload a clear, upright photo with all edges and printed details visible.");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "ID document checks are unavailable. The document has not been confirmed. Please try again later.");
    }
}
