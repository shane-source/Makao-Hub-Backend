# Landlord verification and evidence security

Updated 14 October 2026. This document describes the implemented test-stage flow. It does not certify production identity verification, Kenyan-law compliance, malware detection quality, or a live Supabase/ClamAV deployment.

## Decided scope and deliberately undecided items

Implemented requirements:

- A signed-in account with the active `LANDLORD` role submits four items: ID front, ID back, selfie, and proof of ownership.
- ID and selfie inputs are JPEG/PNG at the backend boundary. Android may select WebP but converts it to JPEG before requesting an upload.
- Ownership proof is **PDF only**, up to 10 MiB and 100 pages.
- Every new evidence file is downloaded privately by the backend, scanned by a local ClamAV daemon, and validated before the row becomes `READY`.
- Images are decoded, bounded, resized when necessary, and re-encoded without source metadata before they become `READY`.
- A scanner outage, timeout, invalid reply, malformed file, or storage failure leaves evidence unconfirmed.
- Existing evidence created before this version cannot silently inherit the new security status. The landlord must explicitly replace it.

Still `TBC` and not invented here:

- ID authenticity and face/liveness-match provider.
- Managed KMS and envelope-encryption provider.
- Document retention/deletion schedule, reviewer permissions, append-only document-view audit, and lawful basis/privacy notice. These require a Kenyan privacy lawyer.
- Which ownership documents are legally sufficient; a PDF is a transport format, not proof that its contents establish ownership.
- Production malware-scanning topology, capacity, alerting, signature SLA, sandboxing, and parser isolation.
- Review time. The UI does not promise 24 hours.

No Flyway migration was added by this change. Existing rows and existing `VERIFIED` role decisions were not rewritten.

## State and request flow

Document states:

```
new request -> UPLOADING -> READY
                  |
                  +-> DELETED when the owner explicitly replaces it
```

Failed confirmation stays `UPLOADING`; it never becomes `READY`. Replacement preserves the old database row and storage object for a future retention policy instead of destroying evidence silently.

Role-verification states used by this flow:

```
NOT_STARTED or REJECTED -> PENDING -> VERIFIED or REJECTED by the review process
```

Evidence cannot be changed while the role verification is `PENDING` or `VERIFIED`.

End-to-end sequence:

1. Android requests `POST /api/v1/landlord/verification/documents/upload-url` with one allowed document type, its fixed MIME type, and `replaceExisting`.
2. Spring Security requires `ROLE_LANDLORD`. `LandlordVerificationService` derives the owner only from the JWT subject, locks that active account row, and confirms that the role is active and editable.
3. The service creates a server-owned object path under `<landlord-id>/evidence-v1/...`. The client cannot choose a bucket, path, or landlord ID.
4. Supabase returns a five-minute signed upload capability with overwriting disabled. The Android upload request contains no Makao JWT.
5. Android uploads the bytes directly to the private verification bucket and then calls `POST /api/v1/landlord/verification/documents/{documentId}/confirm` with its Makao JWT.
6. The service again locks/checks the authenticated landlord and selects the document by both `documentId` and `landlordId`.
7. The backend checks private storage metadata, MIME equality, positive size, and the 10 MiB limit; then performs a bounded authenticated download with redirects disabled.
8. `ClamAvScanner` streams the bytes to a local Unix socket. Only the exact NUL-terminated reply `stream: OK` is accepted.
9. A PDF is parsed and checked for encryption, page count, scripts, external/launch actions, attachments, XFA, AcroForm, and rich-media structures. An image is decoded with dimension bounds and re-encoded without source metadata; the normalized image replaces the uploaded image through the service credential.
10. Only after all checks finish does the entity become `READY`.
11. `POST /api/v1/landlord/verification/submit` accepts only the four current `READY` records, and specifically requires the ownership record to be `application/pdf`. It then records `PENDING`; upload/confirmation never means identity approval.

The `evidence-v1` path prefix is a server-controlled compatibility marker, not a cryptographic attestation. `securityChecked` is true only for `READY` rows on that path. This prevents old rows from being treated as newly scanned without changing historical data.

## API contract and failure modes

| Endpoint | Success | Important failures |
| --- | --- | --- |
| `GET /api/v1/landlord/verification` | Current role-verification state | `401` no/invalid JWT; `403` wrong role |
| `GET /api/v1/landlord/verification/documents` | Metadata only; no object path/read URL/content | `401`; `403` |
| `POST .../documents/upload-url` | Document ID, type, MIME, short-lived signed URL | `400` unsupported type/MIME; `409` existing record or frozen review; rate limit |
| `POST .../documents/{id}/confirm` | `204` after storage, scan, and parser checks | `400` bad/missing/malformed content; `409` replay/legacy record/frozen review; `503` storage or scanner unavailable; rate limit |
| `POST .../submit` | Role verification becomes `PENDING` | `400` required current evidence missing; `409` already pending/verified; rate limit |

Raw storage/scanner/parser messages, object paths, document bytes, and service credentials are never returned. PDFBox diagnostics are disabled so hostile PDF text is not copied to application logs.

## Backend files, classes, and methods

### `pom.xml`

Adds Apache PDFBox `3.0.8`. It parses the bounded ownership PDF after malware scanning. Without it the backend would only recognize a `%PDF-` prefix, which is not structural validation. RECOMMENDED: keep this dependency patched and monitor PDFBox security advisories.

### `src/main/resources/application.yaml`

- `security.verification.clamav-socket-path` reads `CLAMAV_SOCKET_PATH`. An empty value is allowed at startup but all confirmations fail closed with `503`.
- `logging.level.org.apache.pdfbox: OFF` prevents malformed customer-controlled PDF text from entering logs.
- Existing `verification-document-max-bytes` remains 10 MiB. The application and scanner limits must agree.

### `verification/service/ClamAvScanner.java`

- Constructor reads the local Unix-socket path and uses a 20-second deadline.
- `assertClean` rejects empty/oversized data, null/relative/invalid socket paths, and excess local concurrency; streams 8 KiB chunks with ClamAV `INSTREAM`; bounds the reply to 4096 bytes; accepts only `stream: OK`; returns `400` for a virus signature and `503` for every operational/unknown result.
- `writeAll` completes each protocol buffer instead of assuming one socket write is enough.
- `unavailable` produces one fixed message without a filename, path, scanner reply, or document content.

Removing the semaphore permits unbounded scanner pressure. Removing the deadline can pin request threads. Accepting partial/unknown replies would turn scanner failure into approval. Complexity is O(n) time and O(1) protocol-buffer space in addition to the already bounded in-memory input.

### `verification/service/OwnershipPdfValidator.java`

- `validate` checks the byte bound/header, invokes ClamAV first, loads the document with PDFBox, rejects encryption/zero pages/over 100 pages, and traverses reachable PDF objects with identity-based cycle detection.
- Blocked keys/actions prevent scripts, automatic/additional actions, launch/external navigation, form submission/import, attachments, XFA, AcroForm, URI/rich-media/movie/sound actions.
- Traversal and queue size are capped at 100,000 to prevent an attacker from presenting an unbounded object graph.
- `invalid` returns a fixed `400` message.

Removing the graph walk would allow active content through. ClamAV and structural validation address different attacks; neither proves ownership. Complexity is O(v + e) with hard graph and 10 MiB bounds.

### `verification/service/VerificationImageValidator.java`

- `prepare` accepts only declared JPEG/PNG, scans first, asks ImageIO for the actual format, rejects invalid/over-25-megapixel dimensions, subsamples long images, fully decodes, copies pixels into a standard image, and writes a fresh JPEG/PNG without source metadata.
- All readers, writers, image buffers, and graphics contexts are released.
- `invalid` returns one fixed message.

Removing re-encoding would retain EXIF/GPS and parser-specific payloads. The server repeats this work even though Android prepares images, because the client is untrusted. Runtime is O(pixels + bytes), bounded by 25 megapixels and 10 MiB input/output.

### `storage/service/SupabaseStorageClient.java`

- Constructor gives service calls finite connect/read timeouts and disables redirects.
- `createSignedEvidenceUploadUrl` issues non-overwriting upload capabilities for Landlord and Sublessor evidence; this prevents a still-valid URL from replacing already scanned bytes.
- `readVerificationDocument` validates the server-owned path and HTTPS Supabase origin, uses service credentials only in headers, disables redirects, enforces a 15-second deadline, and uses `LimitedEvidenceBody` so the response cannot exceed the expected/maximum bytes.
- `LimitedEvidenceBody` applies reactive backpressure one batch at a time, cancels on overflow, and returns only a fully bounded byte array.
- `storePreparedVerificationImage` is the only overwrite in this flow. It writes only the server-generated normalized JPEG/PNG through the service credential.

These methods are intentionally private-server operations. Exposing the authenticated download URL or service key would bypass document authorization.

### `verification/api/VerificationDocumentUploadUrlRequest.java`

Adds `replaceExisting`. It must be explicitly true before an active evidence row is superseded. The convenience constructor retains compatibility for other roles and defaults to false.

### `verification/api/LandlordVerificationDocumentResponse.java`

Returns ID, type, state, MIME, size, and derived `securityChecked`. It deliberately excludes object path, bytes, signed read URL, and document content. Removing `securityChecked` would let the Android client confuse historical `READY` with the current validation policy.

### `verification/domain/LandlordVerificationDocument.java`

- `usesImmutableEvidencePath` recognizes only server-created `evidence-v1` paths for this landlord.
- `hasCurrentSecurityChecks` requires both that path and `READY`.
- `markReady` stores the validated/normalized size only after checks pass.
- `supersede` moves the old row to `DELETED` without destroying evidence.

The existing partial unique index still permits only one active type per landlord. No historical row is silently backfilled.

### `verification/service/LandlordVerificationService.java`

- Constructor injects both validators in addition to existing ownership, role, repository, storage, and limit dependencies.
- `createDocumentUploadUrl` locks the active owner, enforces editable role-verification state and the type/MIME matrix, requires explicit replacement, flushes the superseded row before inserting a new active row, and requests the non-overwriting signed capability.
- Three-argument `createDocumentUploadUrl` is a compatibility wrapper with replacement disabled.
- `getMyDocuments` returns only bounded required-type metadata belonging to the JWT owner.
- `confirmDocumentUpload` locks/rechecks the owner, loads by owner and document ID, rejects replays and legacy paths, validates metadata, bounded-downloads bytes, scans/parses or normalizes, overwrites only normalized images, marks `READY`, and zeroes temporary byte arrays in `finally` blocks.
- `submitMyVerification` accepts only the four current checked records and a current PDF ownership proof, then applies the legal role-verification transition to `PENDING`.
- `buildObjectPath` creates random, versioned, owner-scoped names; extensions come only from the validated MIME.
- Existing role/account guard methods remain authoritative. The client never supplies an owner ID.

The account row lock serializes concurrent replacements/confirmations for one owner and works with the existing partial unique index. It also keeps a database transaction open while storage/scanning runs; this is safe for test-stage consistency but is a production capacity risk. RECOMMENDED: before production, design a separate durable `SCANNING` state/outbox worker and migration after confirming concurrency and recovery rules.

### Tests

- `ClamAvScannerTest`: exact INSTREAM framing, clean/malware/error/unknown/incomplete/timeout replies, and invalid configuration.
- `OwnershipPdfValidatorTest`: static, malformed, encrypted, scripted, attached/form, external-action, oversized-page-count, and scanner-outage documents.
- `VerificationImageValidatorTest`: format matching, scanner failure, decode/re-encode, and removal of a synthetic private metadata marker.
- `EvidenceBodyLimitTest`: exact-size download and overflow cancellation.
- `LandlordEvidencePolicyTest`: PDF-only policy, non-overwriting capability, explicit replacement, review freeze, ownership selection, scanner outage, normalization-before-ready, legacy blocking, and replay behavior.
- `LandlordVerificationDocumentsControllerTest`: metadata privacy, JWT-derived owner, anonymous/wrong-role denial for reads and mutations.
- Existing constructor/contract/integration tests were updated only for the new dependencies/response fields.

## ClamAV setup for this development machine

RECOMMENDED steps for MX Linux/Debian; verify package/service names on the machine rather than assuming:

1. Install the distribution's `clamav` and `clamav-daemon` packages.
2. Use `clamconf` and the active `clamd.conf` to find the actual `LocalSocket`; do not guess `/run/clamav/clamd.ctl`.
3. Keep the socket local only. Set restrictive ownership and `LocalSocketMode 660`, and grant the backend process user socket access through the narrowest appropriate group/ACL. Never expose clamd's unauthenticated TCP port.
4. Ensure `StreamMaxLength` exceeds the backend's 10 MiB maximum. `RECOMMENDED`: 11M for this application. Keep PDF scanning enabled. Enable “alert when limits are exceeded” if supported by the installed version, because the application accepts only an exact clean reply anyway. Review `MaxScanTime`, extraction, recursion, and archive limits against the 20-second application timeout and host memory.
5. Keep official signatures current with the distribution's FreshClam service and monitor update failures. ClamAV requires a signature database before clamd can scan.
6. Start/restart clamd, confirm the socket exists, and verify `PING`/a harmless stream locally.
7. Set only the path, for example `export CLAMAV_SOCKET_PATH='<the LocalSocket value>'`, in the private runtime environment. It is configuration, not a secret, but it should not be client-controlled.
8. Restart Spring Boot. With no working socket, confirmation is expected to return `503` and remain unconfirmed.

The implementation follows ClamAV's NUL-framed `INSTREAM` protocol: four-byte big-endian chunk sizes followed by a zero-length chunk. See the official [clamd protocol](https://docs.clamav.net/manual/Usage/ClamdProtocol.html), [configuration guide](https://docs.clamav.net/manual/Usage/Configuration.html), and [scanning/signature guidance](https://docs.clamav.net/manual/Usage/Scanning.html).

Do not test with real identity documents until the private bucket, access logs, retention, KMS design, reviewer audit, and privacy/legal controls have been reviewed.

## Verification performed

- Focused backend suite: 26 tests passed.
- Full backend suite: 144 tests passed.
- A fresh temporary PostgreSQL 15 database applied and validated all 53 Flyway migrations; Hibernate schema validation passed.
- Tests used synthetic bytes, a local fake ClamAV socket, mocks, and a temporary database. No Supabase object, real identity document, email, or payment was touched.

## Remaining risks and required confirmation

- Install and test a real, patched ClamAV daemon with current signatures. The fake protocol test proves framing/fail-closed behavior, not detection quality.
- Run a private disposable-file Supabase integration after bucket policy review. Signed-upload no-overwrite behavior was not exercised against live storage in this task.
- Add operational alerts for scan failures/timeouts/signature age without logging PII.
- Isolate PDF parsing/scanning into a constrained worker before production; in-process parsers still expand attack surface.
- Confirm KMS/envelope encryption, retention/deletion, incident response, reviewer access/audit, data-subject handling, and cross-border/storage facts with the provider and a Kenyan privacy lawyer.
- Select and assess an ID/liveness provider. A camera selfie and clean PDF do not verify identity or ownership.

Suggested next task: configure local clamd, run a harmless disposable PDF/image end-to-end through the private development bucket, and inspect only metadata/state transitions—not document contents in logs.
