package com.makaohub.backend.verification.service;

import com.makaohub.backend.verification.domain.VerificationDocumentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static com.makaohub.backend.verification.domain.VerificationDocumentType.*;
import static org.junit.jupiter.api.Assertions.*;

class KenyanNationalIdValidatorTest {
    private static final String FRONT = "REPUBLIC OF KENYA\nSERIAL NUMBER 000000000\nID NUMBER 00000000\nFULL NAMES TEST PERSON\nDATE OF BIRTH 01.01.1990\nSEX FEMALE\nDATE OF ISSUE 01.01.2020";
    private static final String BACK = "DISTRICT TEST\nDIVISION TEST\nLOCATION TEST\nSUB-LOCATION TEST\nPRINCIPAL REGISTRAR SIGN";
    private static final String MAISHA_FRONT = "REPUBLIC OF KENYA\nNATIONAL IDENTITY CARD\nMAISHA NUMBER 000000000\nSURNAME TEST\nGIVEN NAMES PERSON\nNATIONALITY KENYAN\nDATE OF EXPIRY 01.01.2034";
    private static final String ALTERNATE_KENYAN_FRONT = "JAMHURI YA KENYA\nREPUBLIC OF KENYA\nNATIONAL IDENTITY CARD\nID NUMBER 00000000\nSURNAME TEST\nGIVEN NAME PERSON\nSEX MALE\nNATIONALITY KEN\nDATE OF BIRTH 01.01.1990\nDATE OF ISSUE 01.01.2020";
    private static final String MAISHA_BACK = "IDKEN0000000000<<<<<<<<<<<\n0000000F3401010KEN<<<<<<<<<\nTEST<<PERSON<<<<<<<<<<<<<<<";
    private static final String NOISY_FRONT = "REPUBLIC OF KENYA\n1D NUM8ER 00000000\nFUL NAMES TEST PERSON\nDATE 0F BIRTH 01.01.1990\nS3X FEMALE\nSER1AL NUMBER 000000000\nDATE 0F ISSUE 01.01.2020";
    private static final String HEADER = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n";
    @TempDir Path directory;

    @Test void conservativePolicyAcceptsOnlyRecognizedSideSpecificContent() {
        assertDoesNotThrow(() -> KenyanNationalIdValidator.validateText(FRONT, ID_FRONT));
        assertDoesNotThrow(() -> KenyanNationalIdValidator.validateText(BACK, ID_BACK));
        assertDoesNotThrow(() -> KenyanNationalIdValidator.validateText(MAISHA_FRONT, ID_FRONT));
        assertDoesNotThrow(() -> KenyanNationalIdValidator.validateText(ALTERNATE_KENYAN_FRONT, ID_FRONT));
        assertDoesNotThrow(() -> KenyanNationalIdValidator.validateText(MAISHA_BACK, ID_BACK));
        assertDoesNotThrow(() -> KenyanNationalIdValidator.validateText(NOISY_FRONT, ID_FRONT));
        for (String unrelated : List.of("", "ID", "Invoice KENYA 12345678", "Lease agreement",
                "REPUBLIC OF KENYA PASSPORT 00000000", "REPUBLIC OF KENYA ID NUMBER 00000000",
                "DISTRICT OF BIRTH SUB LOCATION")) {
            for (var side : List.of(ID_FRONT, ID_BACK))
                assertEquals(400, assertThrows(ResponseStatusException.class,
                        () -> KenyanNationalIdValidator.validateText(unrelated, side)).getStatusCode().value());
        }
        assertThrows(ResponseStatusException.class, () -> KenyanNationalIdValidator.validateText(FRONT, ID_BACK));
        assertThrows(ResponseStatusException.class, () -> KenyanNationalIdValidator.validateText(BACK, ID_FRONT));
        assertThrows(ResponseStatusException.class,
                () -> KenyanNationalIdValidator.validateText(FRONT.replace("KENYA", "UGANDA"), ID_FRONT));
        assertThrows(ResponseStatusException.class, () -> KenyanNationalIdValidator.validateText(
                FRONT.replace("FULL NAMES", "").replace("DATE OF BIRTH", "").replace("SEX", ""), ID_FRONT));
        for (String required : List.of("DISTRICT", "DIVISION", "LOCATION", "SUB-LOCATION", "PRINCIPAL REGISTRAR"))
            assertThrows(ResponseStatusException.class,
                    () -> KenyanNationalIdValidator.validateText(BACK.replace(required, ""), ID_BACK));
        assertThrows(ResponseStatusException.class,
                () -> KenyanNationalIdValidator.validateText(BACK.replace("LOCATION TEST\n", ""), ID_BACK));
    }

    @Test void printedNameMustMatchEveryMachineReadableNameToken() {
        String front = "REPUBLIC OF KENYA SURNAME TEST GIVEN NAMES PERSON SAMPLE NATIONALITY KENYAN";
        String back = "IDKEN0000000000<<<< 0000000F3401010KEN<<<< TEST<<PERSON<SAMPLE<<<<";
        assertTrue(KenyanNationalIdValidator.sameName(front, back));
        assertFalse(KenyanNationalIdValidator.sameName(front.replace("PERSON", "OTHER"), back));
        assertFalse(KenyanNationalIdValidator.sameName(front, back.replace("TEST<<", "OTHER<<")));
        assertFalse(KenyanNationalIdValidator.sameName(front, "IDKEN0000000000<<<<"));
        assertFalse(KenyanNationalIdValidator.sameName(front.replace("PERSON", "PERS0N"), back));
        assertFalse(KenyanNationalIdValidator.sameName("SURNAME TEST GIVEN NAME PERS0N", "TEST<<PERSON"));
    }

    @Test void olderMachineReadableNameRequiresCompleteExactOrderedName() {
        String front = "REPUBLIC OF KENYA FULL NAMES TEST PERSON SAMPLE DATE OF BIRTH";
        String back = "TEST<PERSON<SAMPLE<<<<<<<<";
        assertTrue(KenyanNationalIdValidator.sameLegacyName(front, back));
        assertFalse(KenyanNationalIdValidator.sameLegacyName(front.replace("PERSON", "OTHER"), back));
        assertFalse(KenyanNationalIdValidator.sameLegacyName(front.replace("SAMPLE", "OTHER"), back));
        assertFalse(KenyanNationalIdValidator.sameLegacyName(front, "TEST<PERSON<OTHER<<<<"));
        assertFalse(KenyanNationalIdValidator.sameLegacyName(front, "TEST<PERSON<SAMPLE"));
        assertFalse(KenyanNationalIdValidator.sameLegacyName(front, "PERSON<TEST<SAMPLE<<<<"));
        assertFalse(KenyanNationalIdValidator.sameLegacyName(front, "IDKYA123456<<"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KENYAN_ID_FRONT_SAMPLE", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "KENYAN_ID_BACK_SAMPLE", matches = ".+")
    void suppliedLocalIdPairMatchesWithoutPersistingItsName() throws Exception {
        var validator = new KenyanNationalIdValidator(System.getenv("TESSERACT_TEST_PATH"));
        byte[] front = Files.readAllBytes(Path.of(System.getenv("KENYAN_ID_FRONT_SAMPLE")));
        byte[] back = Files.readAllBytes(Path.of(System.getenv("KENYAN_ID_BACK_SAMPLE")));
        try {
            var images = new VerificationImageValidator(validator);
            byte[] preparedFront = images.prepare(front, "image/jpeg", ID_FRONT);
            byte[] preparedBack = images.prepare(back, "image/jpeg", ID_BACK);
            try {
                validator.assertMatchingNames(preparedFront, preparedBack);
                byte[] rotatedFront = rotateLeft(preparedFront), rotatedBack = rotateLeft(preparedBack);
                try { validator.assertMatchingNames(rotatedFront, rotatedBack); }
                finally { Arrays.fill(rotatedFront, (byte) 0); Arrays.fill(rotatedBack, (byte) 0); }
            }
            finally { Arrays.fill(preparedFront, (byte) 0); Arrays.fill(preparedBack, (byte) 0); }
        } finally {
            Arrays.fill(front, (byte) 0);
            Arrays.fill(back, (byte) 0);
        }
    }

    @Test void lowConfidenceWordsAndMalformedEngineResponsesCannotPass() throws Exception {
        assertEquals("", KenyanNationalIdValidator.confidentText(tsv(FRONT, "34.9")));
        assertEquals("", KenyanNationalIdValidator.confidentText(tsv(FRONT, "NaN")));
        assertEquals("", KenyanNationalIdValidator.confidentText(tsv(FRONT, "Infinity")));
        assertThrows(java.io.IOException.class, () -> KenyanNationalIdValidator.confidentText("anything"));
        assertThrows(java.io.IOException.class, () -> KenyanNationalIdValidator.confidentText(tsv(FRONT, "bad")));
        KenyanNationalIdValidator.validateText(KenyanNationalIdValidator.confidentText(tsv(FRONT, "35")), ID_FRONT);
        KenyanNationalIdValidator.validateText(KenyanNationalIdValidator.confidentText(tsv(FRONT, "90")), ID_FRONT);
    }

    @Test void missingEngineInvalidPathAndUnsupportedEvidenceFailClosed() {
        for (String path : List.of("", "relative-path", "/no-such-makao-ocr", "bad\0path")) {
            var validator = new KenyanNationalIdValidator(path, 100);
            assertEquals(503, assertThrows(ResponseStatusException.class,
                    () -> validator.validate(new byte[]{1}, ID_FRONT)).getStatusCode().value());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new KenyanNationalIdValidator("/usr/bin/tesseract").validate(new byte[]{1}, SELFIE));
    }

    @Test void processExitMalformedOutputOverflowAndTimeoutFailClosed() throws Exception {
        for (String command : List.of("exit 1", "printf 'invalid'", "head -c 262145 /dev/zero", "exec /usr/bin/sleep 10")) {
            Path fakeEngine = script(command);
            var validator = new KenyanNationalIdValidator(fakeEngine.toString(), 200);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                assertEquals(503, assertThrows(ResponseStatusException.class,
                        () -> validator.validate(new byte[]{1}, ID_FRONT)).getStatusCode().value());
            });
        }
    }

    @Test void processReadsImageFromStdinAndRejectsLowConfidenceOutput() throws Exception {
        String output = tsv(FRONT, "34");
        Path engine = script("/usr/bin/cat >/dev/null\nprintf '%s' '" + output + "'");
        var validator = new KenyanNationalIdValidator(engine.toString(), 2000);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> validator.validate(new byte[65536], ID_FRONT)).getStatusCode().value());
    }

    @Test void denseTextFallbackCanConfirmAReadablePhonePhoto() throws Exception {
        Path engine = script("/usr/bin/cat >/dev/null\n"
                + "if [ \"$8\" = \"11\" ]; then printf '%s' '" + tsv("REPUBLIC OF KENYA", "90") + "'; "
                + "else printf '%s' '" + tsv(FRONT, "45") + "'; fi");
        assertDoesNotThrow(() -> new KenyanNationalIdValidator(engine.toString(), 2000)
                .validate(new byte[65536], ID_FRONT));
    }

    @Test void enhancedFrontFallbackStillRequiresRecognizedIdFields() throws Exception {
        Path calls = directory.resolve("calls");
        Path engine = script("/usr/bin/cat >/dev/null\n"
                + "count=$(/usr/bin/cat '" + calls + "' 2>/dev/null || printf 0)\n"
                + "count=$((count + 1))\nprintf '%s' \"$count\" >'" + calls + "'\n"
                + "if [ \"$count\" -le 2 ]; then printf '%s' '" + tsv("REPUBLIC OF KENYA", "90") + "'; "
                + "else printf '%s' '" + tsv(FRONT, "90") + "'; fi");
        assertDoesNotThrow(() -> new KenyanNationalIdValidator(engine.toString(), 2000)
                .validate(image("UNREADABLE FIRST PASS"), ID_FRONT));
        assertTrue(Integer.parseInt(Files.readString(calls)) >= 3);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TESSERACT_TEST_PATH", matches = ".+")
    void actualTesseractRecognizesSyntheticFieldsAndRejectsAnUnrelatedImage() throws Exception {
        var validator = new KenyanNationalIdValidator(System.getenv("TESSERACT_TEST_PATH"));
        validator.validate(image(FRONT), ID_FRONT);
        validator.validate(image(BACK), ID_BACK);
        for (var entry : List.of(new Object[]{FRONT, ID_FRONT}, new Object[]{BACK, ID_BACK})) {
            byte[] rotated = image((String) entry[0]);
            for (int turns = 1; turns <= 3; turns++) {
                rotated = rotateLeft(rotated);
                validator.validate(rotated, (VerificationDocumentType) entry[1]);
            }
        }
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> validator.validate(image("MAKAO HUB\nRENT RECEIPT\nRENT PAID 10000"), ID_FRONT)).getStatusCode().value());
    }

    private Path script(String command) throws Exception {
        Path file = Files.createTempFile(directory, "trusted-test-engine", ".sh");
        Files.writeString(file, "#!/bin/sh\n" + command + "\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    private static String tsv(String text, String confidence) {
        var output = new StringBuilder(HEADER);
        for (String word : text.split("\\s+"))
            output.append("5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t").append(confidence).append('\t').append(word).append('\n');
        return output.toString();
    }

    private static byte[] image(String text) throws Exception {
        var image = new BufferedImage(1400, 650, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1400, 650);
            graphics.setColor(Color.BLACK); graphics.setFont(new Font("DejaVu Sans", Font.PLAIN, 42));
            int y = 80;
            for (String line : text.split("\n")) { graphics.drawString(line, 40, y); y += 85; }
        } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bytes);
        image.flush();
        return bytes.toByteArray();
    }

    private static byte[] rotateLeft(byte[] bytes) throws Exception {
        var source = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        var rotated = new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_INT_RGB);
        var graphics = rotated.createGraphics();
        try {
            graphics.translate(0, source.getWidth());
            graphics.rotate(-Math.PI / 2);
            graphics.drawImage(source, 0, 0, null);
        } finally { graphics.dispose(); }
        var output = new ByteArrayOutputStream();
        ImageIO.write(rotated, "PNG", output);
        source.flush(); rotated.flush();
        return output.toByteArray();
    }
}
