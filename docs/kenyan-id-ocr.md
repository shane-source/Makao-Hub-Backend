# Kenyan national-ID content screening

## Scope and limits

New ID-front/back confirmations for Landlord, Sublessor and Renter must pass local OCR
content screening. The same existing confirmation endpoints are used; no new endpoint,
database column, migration, document replacement, or identity approval is introduced.
Existing confirmed documents and role decisions are **not** retroactively changed.
They must not be described as having passed this OCR policy. Whether to recheck
previously confirmed evidence is **TBC** and requires the owner's approval.

**Important:** recognizing printed fields does not prove an ID is genuine, belongs
to the uploader, matches the selfie, or matches the other ID side. A deliberately
fabricated image containing the expected words can pass. This is a filter for
unrelated/unreadable uploads, not an identity-verification provider. OCR rejection or
unavailability leaves the upload unconfirmed; only an OCR-screened image reaches the
existing authorised-staff review workflow. Never label an OCR result “identity verified”.

**RECOMMENDED testing policy implemented here:** require recognizable printed fields
on the requested side. An older-format front requires a Kenyan heading, numeric text,
an ID label and at least three of the full-name, birth-date, sex/gender, serial-number,
issue-date and district labels. Similar birth/issue phrases cannot count twice unless
both distinguishing words are present. Limited edit-distance tolerance applies only to these known
labels so ordinary OCR character substitutions do not reject an otherwise readable ID.
A Maisha-style front requires its Maisha label, name fields, nationality and expiry
label. An older-format back requires district, division, location, sub-location and
issue/registrar labels; a Kenyan ICAO-style machine-readable zone is the alternative
back signal. These are conservative application screening rules, **not** an official
specification of all Kenyan IDs. Coverage, orientation, glare, language and
acceptance accuracy still need validation against lawfully obtained, consented
examples before rollout. Passports and other identity documents are not supported.

The [Kenyan Embassy's information and samples](https://kenyaembassydc.org/idprep/)
and [NRB's Maisha information](https://www.usajili.go.ke/nrb) show why card
generations must not be assumed interchangeable. Additional layout support and any
authoritative ID/face provider remain **TBC**; no legal-ID validity decision is made.

## Design and API behavior

The existing flow is:

1. Authenticate and check the role, account, document ownership and editable state.
2. Check private-storage metadata and download size against the recorded MIME/limit.
3. Scan images using ClamAV, decode the actual JPEG/PNG, bound dimensions, re-encode
   without carrying source metadata.
4. For `ID_FRONT`/`ID_BACK` only, send that normalized image to local Tesseract in
   sparse-text mode. If the side-specific policy does not pass, retry once in bounded
   dense-block mode for compact card text commonly seen in phone photos. If a portrait
   ID front still fails, retry the same field policy on a bounded grayscale, sharpened,
   contrast-stretched image. If a portrait image still fails, retry one sparse pass
   after each bounded 90-degree rotation.
5. Fail closed when the requested ID side is not recognized or OCR is unavailable.
   No OCR text, identity value or native diagnostic is logged or returned.
6. Store the normalized image and mark the existing upload `READY` only after OCR passes.
   Any image, malware or OCR failure leaves it `UPLOADING`; no upload confirms identity.

Selfies still receive malware/image checks without OCR. Ownership/rental agreements
remain PDF-only under the existing header, PDFBox parser, active-content and malware
checks. OCR is not used to decide whether a file really is a PDF.

The application uses the existing owner-scoped upload/confirm API contract. A failed
image must be replaced using the existing explicit replacement flow when necessary;
never overwrite an already confirmed evidence object. No server-selected role,
ownership check, storage protection or rate limit was removed. Existing transaction
and user-lock behavior is unchanged; a slow scan can hold that lock until the
bounded scan completes. Async document processing would need a separate design.

## File and method walkthrough

### `src/main/java/com/makaohub/backend/verification/service/KenyanNationalIdValidator.java`

One shared Spring service, using Tesseract **5.x** CLI (tested with **5.3.0**) and Java
21 standard-library process/I/O/concurrency APIs. No OCR Java dependency is added.
CLI/TSV behavior follows the [official Tesseract documentation](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html).

- Public constructor injects the trusted executable path. Package-private constructor
  changes the deadline for tests only. Removing either breaks service configuration
  or deterministic timeout tests; neither supplies user-controlled command arguments.
- `validate`: permits only ID front/back, bounds nonempty image bytes, invokes OCR and
  requires the side-specific policy. A failed ID front receives one bounded preprocessing
  fallback through `matchesEnhancedFront`; `enhanceForOcr` caps its longest side at 2400
  pixels, converts to grayscale, sharpens and trims the darkest/lightest one percent,
  while `stretchContrast` changes pixels only and never changes the required fields.
  If an initially unreadable image is portrait,
  `matchesRotatedPortrait` retries bounded left/right rotations without persisting them.
  Rejection and OCR unavailability propagate to the confirmation endpoint.
- `readText`, security-critical logic in order:
  - Require an absolute configured executable path. Invalid paths fail as unavailable.
    This prevents a request or working-directory executable from selecting a command.
  - Acquire one of two process permits without queueing. Overload fails closed;
    this limits concurrent native processes **per application instance**.
  - Start an executor and the trusted executable with a fixed argument array:
    `stdin stdout -l eng --oem 1 --psm <mode> tsv`, where `<mode>` is the internal
    numeric constant `11` for sparse text or `6` for the one bounded dense fallback.
    No shell, filename, uploaded text or extracted ID value is interpolated into a
    command or URL.
  - Discard stderr rather than logging native diagnostics; diagnostics can include
    document text. Set `OMP_THREAD_LIMIT=1` to limit native thread proliferation.
  - Write normalized image bytes through stdin while reading stdout concurrently.
    This avoids deadlocking when either process pipe fills. No document temp file
    or third-party API request is created by this integration.
  - Read at most 262,145 bytes; more than the 262,144-byte response cap fails closed.
    Join input writing, require zero exit status, parse TSV and wipe the output byte
    array in a `finally` block.
  - Bound process I/O/execution by a 20-second deadline. Always destroy the process
    and close its pipes **before** executor shutdown joins workers; this prevents a
    blocked pipe from turning timeout handling into an indefinite wait.
  - Interrupted work restores the Java interrupt flag. Process failures expose only
    a fixed error message, not native output. Always return the concurrency permit.
- `confidentText`: requires the expected TSV header and uses only word rows with
  finite confidence in `[35,100]`. **RECOMMENDED** threshold 35 is a screening default,
  not a calibrated probability or official ID requirement. Low-confidence text
  cannot satisfy the field policy. Raw TSV/recognized text is never logged or saved.
  Malformed confidences fail closed. Removing this step makes poor scans easier to
  accept and malformed native replies indistinguishable from valid results.
- `validateText`: normalize case/separators, then require the side-specific field
  combination described above. `hasApproximate` applies a bounded Levenshtein distance
  only to known front-label phrases; values are never fuzzy-matched or retained. This
  does not compare names, numbers or dates against a registry or another document.
- `has` uses phrase boundaries; `editDistance` uses two bounded integer rows rather than
  a matrix. Removing these checks accepts unrelated legible text. `invalid` and
  `unavailable` construct fixed, PII-free HTTP errors so the
  caller can distinguish replacement/retake from a service outage.

No identity fields or OCR scores are returned to clients. Best-effort byte-array
wiping does not guarantee erasure of immutable Java Strings or native process memory.
The host's swap, crash dumps, process restrictions and patching remain operational
responsibilities; this is not an OS sandbox for the native OCR engine.

### `src/main/java/com/makaohub/backend/verification/service/VerificationImageValidator.java`

- Constructor injects `KenyanNationalIdValidator` beside the existing ClamAV scanner.
  This centralizes required OCR screening for all three roles, rather than copying it.
- `prepare(bytes, contentType, documentType)` replaces the former two-argument method.
  It rejects unsupported document types first. Existing MIME/size/dimension checks,
  malware scanning, decoding and metadata-free encoding are retained. Before returning
  a prepared ID image, it requires OCR success; selfies skip OCR. Every OCR failure wipes
  the prepared byte array and propagates, so the role service cannot store it or mark the
  upload ready. Putting OCR before decoding would send unchecked originals to the engine.

### Role services

`src/main/java/com/makaohub/backend/verification/service/LandlordVerificationService.java`,
`SublessorVerificationService.java`, and `RenterVerificationService.java` each change
only `confirmDocumentUpload`: pass the stored document type to `prepare`. The type
comes from the owned server record, not a confirm-request flag. Removing that
argument would prevent compilation; skipping the shared validator would bypass image
normalization, malware checks and required OCR. Existing role/owner/editability checks still execute first, and storage
overwrite/`markReady` still execute only after successful preparation.

### `src/main/resources/application.yaml`

Adds `security.verification.tesseract-path`, supplied by `TESSERACT_PATH` with default
`/usr/bin/tesseract`. It is executable configuration, not an API key. Removing the
explicit YAML entry still leaves the service's same fallback, but hides the setting
from the central configuration. No external OCR credential is needed.

### Tests

- `src/test/java/com/makaohub/backend/verification/service/KenyanNationalIdValidatorTest.java`:
  `conservativePolicyAcceptsOnlyRecognizedSideSpecificContent` tests side/country/field
  failures, including legacy and Maisha-style signals;
  `lowConfidenceWordsAndMalformedEngineResponsesCannotPass` tests TSV;
  `missingEngineInvalidPathAndUnsupportedEvidenceFailClosed` tests configuration;
  `processExitMalformedOutputOverflowAndTimeoutFailClosed` tests native-process
  failure bounds; `processReadsImageFromStdinAndRejectsLowConfidenceOutput` tests
  streaming; `denseTextFallbackCanConfirmAReadablePhonePhoto` tests the second OCR
  layout pass; `actualTesseractRecognizesSyntheticFieldsAndRejectsAnUnrelatedImage`
  exercises real OCR when `TESSERACT_TEST_PATH` is set. `script` creates trusted
  temporary test-only engines; `tsv`, `image` and the rotation fixture use synthetic
  data, never real customer IDs. These tests do **not** establish authenticity.
- `VerificationImageValidatorTest`: existing image tests use `SELFIE` explicitly;
  the constructor supplies mocked OCR. The ID-side test proves both sides call OCR
  after valid decoding and that rejection/unavailability cannot produce prepared evidence.
- `LandlordEvidencePolicyTest`, `SublessorEvidencePolicyTest`, `RenterEvidencePolicyTest`:
  update `imageReadinessRequiresServerNormalizationToSucceed` to expect the stored
  ID type. Renter's scanner-failure test also passes the selfie type.
  `imageSecurityFailureNeverBecomesReadyOrReachesPreparedStorage` proves hard image
  validation failures stay `UPLOADING`. `wrongRoleCannotReachDocumentsStorageOrImageValidation`
  proves 403 precedes evidence access. Existing foreign-owner tests remain.

Removing these tests would not alter runtime logic, but would lose regression proof
for the acceptance, failure and authorization boundaries each test names.

## Setup and verification

Verification result: compilation and **276 tests passed**, with zero failures or
errors and no skipped tests when `TESSERACT_TEST_PATH` is set. This includes the actual local
Tesseract 5.3.0 test, PostgreSQL 15 clean-database migrations through V60, and the
admin security-boundary suite. This does not certify the current running server,
real-card accuracy, or an Android end-to-end upload. No production database or
personal document was accessed.

Install on the backend's Debian-based development host:

```bash
sudo apt install tesseract-ocr tesseract-ocr-eng
tesseract --version
tesseract --list-langs
```

Ensure `eng` appears. Restart the backend using its usual private environment and
existing ClamAV socket configuration. The default executable path needs no extra
setting if installed at `/usr/bin/tesseract`. Do not disable scanning/OCR if either
engine is unavailable. A running old backend process must be restarted to load code.

Run the OCR test including its actual-engine check:

```bash
TESSERACT_TEST_PATH=/usr/bin/tesseract ./mvnw \
  -Dtest=KenyanNationalIdValidatorTest \
  -DargLine=-javaagent:/home/hp/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar test
```

Use a dedicated test account. A blank, malformed, oversized or malware-positive image
must stay unconfirmed. An unrelated picture, receipt, reversed ID side or unavailable
OCR must also stay unconfirmed and must never reach the staff-review queue. A
non-PDF renamed `.pdf` must still fail PDF validation. Do not post a real
ID, OCR text or a signed document URL into tickets, screenshots, logs or this chat.

## Remaining risks, confirmations and next task

- OCR is spoofable; self-made text images, two different people's cards, a copied
  ID, or another person's selfie are not excluded by this content heuristic.
- Existing confirmed evidence has not been rechecked. No data-changing migration
  was written. Select an explicit recheck policy before applying one to old accounts.
- **TBC:** consented second-generation/Maisha fixtures, accuracy targets, and an
  authoritative verification/face-match provider. No official Kenyan card-acceptance
  claim is supported by these synthetic tests.
- **RECOMMENDED next task:** test a consented real card through the Android gallery and
  camera flows, then calibrate additional layouts before OCR is used for any decision.
- Confirm ID processing, consent, retention and access requirements with a Kenyan
  privacy lawyer and any future identity provider. No Safaricom/payment behavior was
  changed, and no payment-provider confirmation is needed for this OCR-only change.
