# Sublessor workspace backend contracts

15 October 2026. Companion Android/code guide: `/home/hp/Desktop/makao-hub-frontend/SUBLESSOR_IMPLEMENTATION.md`. Scope is the approved shared-home fields, verification, preferences and discovery needed by the selected Sublessor screens. No matching weights or payment rules are chosen.

All Java paths below are relative to `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/`. Actor IDs come from validated JWT subjects, not request bodies. Existing Spring Security role routes remain mandatory. New JDBC services independently check the database role and ACTIVE account; household queries also match the owner. Existing JPA services are extended locally, not rewritten.

## HTTP and data contract

| Route | Method | Meaning |
| --- | --- | --- |
| `/api/v1/sublessor/listings/{listingId}/household` | GET/PUT | Own optional deposit, household, occupancy, lifestyle and amenities; writes DRAFT only |
| `/api/v1/sublessor/roommate-preferences` | GET/PUT | Own Sublessor answers and opt-in |
| `/api/v1/renter/roommate-preferences` | GET/PUT | Own Renter answers and opt-in |
| `/api/v1/sublessor/roommate-discovery?page=0&size=20` | GET | Eligible renters; size 1–50, bounded page |
| `/api/v1/sublessor/roommate-discovery/{renterId}` | GET | Fresh eligibility-checked public profile |
| `/api/v1/sublessor/verification/documents` | GET | Own active metadata only, no object path/URL/bytes |
| `/api/v1/sublessor/verification/documents/upload-url` | POST | Required evidence type/MIME; optional explicit `replaceExisting` |
| `/api/v1/sublessor/verification/documents/{documentId}/confirm` | POST | Check actual storage bytes; 204 only after success |

Existing Sublessor application reject now requires JSON `{"reason":"..."}` (nonblank, at most 500 characters). Existing clients must send it. Application responses now include nullable `rejectionReason`; old historical rejected records remain null. Media responses add boolean `confirmed` so clients can distinguish an allocated row from a confirmed upload. These are additive response changes; the reject request is an intentional stricter contract.

Household money uses whole-KES `Long`, matching the existing listing contract; it is never float/double. Missing deposit is null, not a fabricated zero. This table does not yet change checkout's authoritative pricing.

Preferences require seven named categorical answers and positive ordered whole-KES budgets. Unknown enum strings, reversed budgets and oversized fields fail validation. `discoveryOptIn` defaults false. Optional public text is contact-masked; it does not contain documents, phone/email columns or the privately stored listing pin. A public area label is supplied by the member, not copied from an exact address.

## New migrations

`/home/hp/Desktop/makao-hub-backend/src/main/resources/db/migration/V54__add_shared_home_details_and_roommate_preferences.sql` creates three empty tables. `sublessor_household_details` has one owned-listing key and bounded nullable fields. `roommate_preferences` has a `(user_id,role)` primary key, answer/budget constraints and a partial opted-in Renter index. `roommate_preference_audit` stores each save/opt-in state without public text; its `reject_roommate_preference_audit_mutation` trigger rejects UPDATE/DELETE. Removing the tables breaks new APIs; removing constraints/trigger permits invalid answers or altered consent history. Database administrators/table owners can bypass ordinary safeguards: deployment still needs a least-privilege application role.

`V55__add_shared_application_rejection_reason.sql` adds a nullable bounded reason column. It does not invent reasons or rewrite historical rows. Both migrations were tested on an isolated clean database; this task did not migrate live Supabase data. Never edit them after application; add a new migration for corrections.

## Household files and methods

`property/api/SublessorHouseholdController.java`: `Amenity` is an explicit allowed set; `Household` is the validated wire record. `get` delegates own reads; `save` delegates validated writes; both extract the actor directly from the JWT subject. Constructor injects the service. Removing validation permits invalid input to reach persistence; removing JWT derivation introduces impersonation risk.

`property/service/SublessorHouseholdService.java`: constructor injects JdbcTemplate. `owned(actor,listing,lock)` uses parameterized values, joins ACTIVE users and the actual SUBLESSOR role, matches both IDs, and optionally locks the listing row. Missing/wrong-owner records return the same 404. `get` calls it before reading supplemental fields and returns nulls for an absent record. `save` locks ownership first, requires DRAFT, and atomically upserts only that listing's fields. `parse` reconstructs the small enum set. Removing owner checks causes IDOR; removing the lock lets draft edits race state changes that use the same database row lock; removing the state check permits published-listing edits through a side route. Note: existing JPA listing transitions should also be audited for comprehensive concurrent-edit behavior; this task does not claim all legacy transitions are race-proof.

## Preference/discovery files and security walkthrough

`roommate/api/RoommatePreferencesController.java`: `Preferences` validates enum strings, size, positive budgets and `isBudgetRangeValid`; `PublicProfile` and `DiscoveryPage` explicitly shape the response. `sublessorGet`/`sublessorSave` and `renterGet`/`renterSave` select a fixed server role and the JWT's actor. `discover` passes bounded paging inputs for service validation; `profile` passes only the requested public target. `id` extracts the authenticated subject. Constructor wires the service. Removing a route breaks the corresponding Android or future Renter editor call; accepting a request-supplied actor/role instead would be unsafe.

`roommate/service/RoommatePreferencesService.java` constructor injects JdbcTemplate and the existing contact masker. Security-critical sequence, in execution order:

1. `requireActor` accepts only RENTER/SUBLESSOR, then uses parameterized SQL to require ACTIVE status and that real assigned role. If requested, it separately requires that role's VERIFIED record. This prevents trusting JWT/UI state alone or inheriting another role's approval.
2. `get` checks the caller before selecting only its composite preference key. Absent preferences return 404, not fake defaults.
3. `save` locks the account row, calls `requireActor` (VERIFIED mandatory when opting in), upserts the exact answer fields with bound parameters, then appends an audit event within the same transaction. A failure rolls back both. This prevents injection, cross-account writes and missing consent history. It masks public free text before storage; it does not claim encryption.
4. `VISIBLE` joins current ACTIVE accounts, actual RENTER membership, RENTER-specific VERIFIED state and `discovery_opt_in=TRUE` every time. The stored flag alone never makes a suspended/wrong-role user visible.
5. `requireDiscovery` independently requires the viewer's verified Sublessor role and its own opt-in. Without it, an unverified host could scrape consenting renters.
6. `discover` checks page/size before a parameterized LIMIT/OFFSET query and a count query; sort is UUID, not a hidden compatibility rank. `profile` applies the same visibility query plus target ID; knowing an ID cannot bypass opt-out. A committed opt-out is excluded from subsequent requests; concurrent in-flight/previously read responses cannot be recalled.
7. `read` maps only the defined fields. `publicProfile` masks member name and free text again and constructs the small public response. `mask` reuses the established contact masker. `unavailable` gives a generic 404 instead of disclosing why a particular renter is hidden.

Removing steps 1/4/5/6 causes privilege/consent leaks; removing step 3's transaction loses atomicity; removing step 7 permits obvious contact-sharing bypass through public text. Pattern masking is not complete detection of all encoded/obfuscated PII and is not an automatic identity check.

## Separate Sublessor verification

`verification/service/SublessorVerificationService.java` retains role-specific storage and verification. Constructor now receives existing upload limits, PDF validator and image validator. Method guide:

- `getMyVerification`: own Sublessor status, never another role's approval.
- `createDocumentUploadUrl` (four arguments): lock active account; require editable NOT_STARTED/REJECTED; validate required type/MIME; find active same-type evidence; explicitly supersede only when replacement requested; preserve old record/object; mint a non-upsert ticket for its immutable path. Three-argument overload delegates with replacement false for existing callers.
- `getMyDocuments`: only own four active evidence types; metadata and current-check flag, never raw bytes/storage paths/signed URLs.
- `confirmDocumentUpload`: full check sequence below; no READY until every operation succeeds.
- `submitMyVerification`: lock active Sublessor; collect only current checked READY evidence; require PDF agreement and both ID sides/selfie; reject PENDING/VERIFIED resubmission; mark only the SUBLESSOR role PENDING. Document readiness is not role verification.
- `buildObjectPath`: actor prefix plus random immutable evidence path. `requireActiveSublessor`, `lockActiveSublessor`, `requireEditableVerification`: account/role/edit serialization, blocking edits during review/after approval. `extensionFor` allowlists suffixes; `toResponse` maps role status; error helpers return controlled non-sensitive HTTP failures.

Confirmation walkthrough:

1. Lock/check actor and editable role state before reading a document, preventing other owners or review-state modification.
2. Query by document ID **and** actor ID, require UPLOADING and the current immutable path policy. Old rows are not silently blessed as scanned.
3. Read actual storage metadata; reject empty, oversized or mismatched MIME objects. Client-declared size/type alone is insufficient.
4. Download through the existing bounded reader and check actual byte length equals metadata.
5. Agreement: require `application/pdf`, run existing `OwnershipPdfValidator` (reused byte policy/ClamAV/parser), then mark READY. The helper's class name is historical; it validates bytes rather than proving ownership/tenancy.
6. ID/selfie: scan/decode/normalize through `VerificationImageValidator`, bound normalized size, store prepared bytes, then mark READY. Metadata stripping avoids retaining image location metadata; decoding is not face matching.
7. `finally` overwrites temporary original/prepared arrays. Any exception before readiness rolls back the entity transition; scanner unavailability does **not** pass. External storage writes cannot be rolled back with SQL and must be recoverable by an explicit retry/replacement.

`verification/domain/SublessorVerificationDocument.java`: `usesImmutableEvidencePath` recognizes the versioned Sublessor prefix; `hasCurrentSecurityChecks` requires this prefix plus ready state/size; `supersede` releases active-type status while retaining evidence history. Removing them would allow legacy rows to masquerade as newly checked evidence or replacement to overwrite reviewed material.

`verification/repository/SublessorVerificationDocumentRepository.java`: added the active-type bounded query used by document metadata. It constrains caller/type/state before returning records; unbounded history is not sent to Android.

`verification/api/SublessorVerificationDocumentResponse.java`: explicit metadata-only record. `SublessorVerificationController.java` passes explicit replacement, exposes metadata GET, validates inputs and derives the owner from JWT; no object URL is included in metadata responses. Existing verification endpoints remain role-specific.

`storage/service/SupabaseStorageClient.java`: renamed the existing non-upsert evidence ticket method to `createSignedEvidenceUploadUrl` because both roles reuse it; behavior remains private and immutable. `LandlordVerificationService.java` and its policy test now call that generic name. No second storage client or relaxed uploader was introduced. Removing non-upsert semantics enables changing a file after it was checked.

## Application and media changes

`renter/domain/SublessorRentalApplication.java`: adds nullable `rejectionReason`, getter and `reject(String)` validation before the existing legal transition; the old transition method remains for compatible internal callers. Removing the field loses the applicant's explanation.

`renter/api/SublessorApplicationController.java`: nested validated `Rejection` body is mandatory for the reject endpoint; passes its reason to the service. `renter/service/SublessorApplicationReviewService.java`: `reject` forwards reason to the domain; `toResponse` includes it. `SublessorRentalApplicationService.java`: response mapping also includes it for the renter. `renter/api/RentalApplicationResponse.java`: new nullable component plus old five-argument constructor for unrelated existing callers. Removing any mapping leaves one party unable to see the reason; controller validation prevents blank/unbounded reasons.

`property/api/ListingMediaResponse.java`: adds `confirmed`, retaining a compatibility constructor. `SublessorListingMediaService.toResponse` and `LandlordListingMediaService.toResponse` both derive it from actual `confirmedAt`, not existence. Removing the mappings would misleadingly label real uploads as incomplete or vice versa.

`property/repository/SublessorListingMediaRepository.java`: adds type-aware confirmed-existence query. `SublessorListingService.requireConfirmedMedia` uses IMAGE specifically. This closes the video-only publication loophole without inventing a photo count. Other publish checks remain: active role, owned listing, role verification and location.

## Tests, failure modes and limits

Final run: **165 backend tests passed**, zero failures/errors/skips, including clean-schema application of all **55 Flyway migrations** and the new publication guard. The configuration-dependent `MakaoHubBackendApplicationTests` was explicitly excluded. Android: **46 tests passed** and debug APK assembled. Backend `git diff --check` passed.

New/updated tests and purpose:

- `verification/service/SublessorEvidencePolicyTest`: malformed, stale/unconfirmed and checked-role evidence cases; adapted from existing Landlord policy tests, without fake identity approval.
- `verification/api/SublessorVerificationDocumentsControllerTest`: anonymous/wrong roles, JWT-derived owner and metadata-only responses. Landlord policy references updated only for renamed shared storage method.
- `roommate/api/SharedHomeAuthorizationTest`: JWT-only owner identity, wrong-role/anonymous access and invalid preferences.
- `roommate/service/SharedHomeIntegrationTest`: actual PostgreSQL/Flyway schema; owner/draft isolation, current-role verification, opt-out, contact masking, bounded pages and append-only audit. Synthetic fixture respects the existing automatic role-verification trigger.
- `property/service/SublessorPublicationTest.videoOnlyCannotSatisfyTheRequiredPhoto`: a confirmed generic media record cannot satisfy IMAGE requirement; no publish/save occurs on failure.
- Android `SublessorContractTest` and `RentalChatSocketTest`: wire payloads, role guard, nullable household, answer validation, actual roommate destinations and server-masked echo.

400 means invalid inputs/file; 403 means role/verification/discovery access denied; 404 hides unavailable owned/public records; 409 means state conflict/explicit replacement needed; scanner/provider failure remains failure, not readiness. A separate normal-runtime application-context smoke test requires real deployment configuration and was excluded from the isolated suite. No SMS/email/real payment or live database mutation was performed.

Fixed answer comparisons O(1), page mapping O(page size), file checks O(bytes) with configured bounds. Database offset/count work grows with rows; no O(1) claim. Older application/listing/interest APIs are not newly server-paginated by this task.

## Risks, testing, provider/legal review and next task

Risks remain: physical-device UI/camera/map validation pending; legacy listing transition concurrency warrants audit; no liveness provider/KMS decision; no new listing moderation or direct profile-started-chat contract. The existing contract's whole-KES display deposit is not automatically checkout pricing. Matching weights/financial commissions remain TBC.

Test next: restart using valid private runtime settings, apply V54/V55 through Flyway, install APK and exercise one synthetic role-specific verification-to-published-room journey. Test scan outage, malformed files, expired tickets, another owner's IDs, opt-out and socket reconnect. Do not upload real ID documents for development acceptance.

Provider/lawyer: confirm Supabase bucket privacy, retention and envelope encryption, ClamAV operations, biometric-provider claims, consent and Kenya data-protection obligations. No financial custody instruction was implemented; regulated partner and commission/refund/payout rules remain separate confirmations.

**RECOMMENDED next task:** device acceptance followed by a Renter preferences editor consuming the new API; only implement scoring after explicit weights are agreed.
