package com.makaohub.backend.verification.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Scan first, then accept only bounded, readable PDFs without interactive/embedded payloads. */
@Service
public class OwnershipPdfValidator {
    private static final Set<String> BLOCKED_KEYS = Set.of("JS", "JavaScript", "AA", "OpenAction",
            "EmbeddedFiles", "EF", "XFA", "AcroForm", "RichMediaContent", "RichMediaSettings");
    private static final Set<String> BLOCKED_ACTIONS = Set.of("JavaScript", "Launch", "GoToR", "GoToE",
            "SubmitForm", "ImportData", "URI", "Rendition", "Movie", "Sound");
    private final ClamAvScanner scanner;

    public OwnershipPdfValidator(ClamAvScanner scanner) { this.scanner = scanner; }

    public void validate(byte[] bytes) {
        if (bytes == null || bytes.length < 8 || bytes.length > 10_485_760
                || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) throw invalid();
        scanner.assertClean(bytes);
        try (var pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > 100) throw invalid();
            var visited = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
            var pending = new ArrayDeque<COSBase>();
            pending.add(pdf.getDocument().getTrailer());
            int edges = 0;
            while (!pending.isEmpty()) {
                if (++edges > 100_000) throw invalid();
                COSBase value = pending.removeFirst();
                if (!visited.add(value)) continue;
                if (value instanceof COSObject indirect) {
                    if (indirect.getObject() != null) pending.add(indirect.getObject());
                } else if (value instanceof COSDictionary dictionary) {
                    for (COSName name : dictionary.keySet()) {
                        if (BLOCKED_KEYS.contains(name.getName())) throw invalid();
                        COSBase child = dictionary.getDictionaryObject(name);
                        if (COSName.S.equals(name) && child instanceof COSName action
                                && BLOCKED_ACTIONS.contains(action.getName())) throw invalid();
                        if (child != null) pending.add(child);
                        if (pending.size() > 100_000) throw invalid();
                    }
                } else if (value instanceof COSArray array) {
                    for (COSBase child : array) { if (child != null) pending.add(child); }
                    if (pending.size() > 100_000) throw invalid();
                }
            }
        } catch (IOException | IllegalArgumentException failure) { throw invalid(); }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Choose a readable, unencrypted ownership PDF without scripts, attachments or interactive forms (up to 10 MB and 100 pages).");
    }
}
