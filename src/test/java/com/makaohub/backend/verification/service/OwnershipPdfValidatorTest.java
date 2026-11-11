package com.makaohub.backend.verification.service;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipPdfValidatorTest {
    private final OwnershipPdfValidator validator = new OwnershipPdfValidator();

    @Test void staticPdfIsAcceptedWithoutChangingIt() throws Exception {
        byte[] pdf = pdf(null, false);
        byte[] original = pdf.clone();
        assertDoesNotThrow(() -> validator.validate(pdf));
        assertArrayEquals(original, pdf);
    }

    @Test void headerSpoofEncryptedAndActivePdfsAreRejected() throws Exception {
        assertRejected("%PDF-1.7\nnot-a-document".getBytes());
        assertRejected(pdf(null, true));
        for (String key : new String[] {"JavaScript", "OpenAction", "EmbeddedFiles", "AcroForm", "XFA"})
            assertRejected(pdf(key, false));
    }

    @Test void actionAndPageLimitsAreEnforced() throws Exception {
        try (var pdf = new PDDocument()) {
            for (int i = 0; i < 101; i++) pdf.addPage(new PDPage());
            var bytes = new ByteArrayOutputStream(); pdf.save(bytes); assertRejected(bytes.toByteArray());
        }
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            var action = new COSDictionary(); action.setName(COSName.S, "Launch");
            pdf.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Action"), action);
            var bytes = new ByteArrayOutputStream(); pdf.save(bytes); assertRejected(bytes.toByteArray());
        }
    }

    private void assertRejected(byte[] pdf) {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> validator.validate(pdf)).getStatusCode().value());
    }
    private byte[] pdf(String blockedKey, boolean encrypted) throws Exception {
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            if (blockedKey != null) pdf.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName(blockedKey), new COSDictionary());
            if (encrypted) pdf.protect(new StandardProtectionPolicy("synthetic-owner", "synthetic-user", new AccessPermission()));
            var bytes = new ByteArrayOutputStream(); pdf.save(bytes); return bytes.toByteArray();
        }
    }
}
