# Makao Hub staff/admin backend

## Agreed scope

Dedicated invite-only staff accounts; existing email OTP for staff MFA; hide already
published listings until approval. No public Admin registration or automatic promotion
of customer accounts. This is backend/API work: the staff website and Android review
controls have not been built or connected by this change.

Implemented: staff invitations/acceptance, password plus email-OTP sign-in, refresh,
logout and recovery; least-privilege staff roles; assigned verification cases and
audited private document delivery; listing moderation and owner resubmission; masked
message review/warnings; text safety reports; read-only payment/booking records;
append-only audit events, own activity and bounded audit export.

**Still blocked:** admin financial mutations, full mock-refund/dispute resolution and
private dispute/report evidence uploads. No dispute/refund policy, custody arrangement,
two-person approval or evidence-storage policy is invented. The older admin payment
release route is now denied at the HTTP security boundary. Existing non-admin payment
code has not been redesigned. Read-only financial records are not a statement that
Makao Hub holds customer funds. Do not label all historic records “mock” without
checking the environment/provider that created them.

**RECOMMENDED testing defaults implemented:** invitations expire after 24 hours;
staff MFA sessions last at most eight hours without refresh extending that ceiling.
These are security defaults for testing, not final production policy. Confirm session,
invitation, retention and recovery policy before production. OTP lifetime, five-attempt
lockout, sending limits, password encoding and JWT TTLs reuse the existing auth code.

## Permissions and data model

| Staff role | Allowed scope |
| --- | --- |
| `STAFF_LEAD` | Invite/change other staff; assign verification reviewers; directory; global audit/export |
| `VERIFICATION_REVIEWER` | Own assigned verification cases; document access additionally requires `can_view_documents` |
| `SAFETY_MODERATOR` | Whole/shared listing moderation, masked message flags, safety reports |
| `PAYMENT_REVIEWER` | Read-only payments and booking/hold/release history |

All staff may read their own activity. A lead is not automatically a document reviewer
or payment reviewer. Changing another staff member's role/permission disables their
sessions and refresh tokens and increments their authorization version. Self-permission
changes are refused. Staff remain `ADMIN` in the existing single-role account model;
staff job permissions are separate, not new public account roles. Existing public role
switching already refuses staff accounts.

New JDBC-managed tables: `staff_accounts`, `staff_invitations`,
`staff_login_challenges`, `staff_sessions`, `staff_access_proofs`, `admin_commands`,
`admin_audit_events`, `verification_assignments`, `verification_document_reviews`,
`admin_message_reviews`, `user_reports`. OTPs stay in `auth_otp_challenges`; they are
not copied into `users`. Invitation email addresses use a domain-separated HMAC;
invitation codes and access-token proofs use hashes. Raw codes/tokens are not stored
in these new tables. Account email storage still follows the existing `users` schema;
this change does **not** establish application-layer email/document encryption or KMS.

The new module uses Spring JDBC, not new JPA entities. Existing JPA authentication
services are reused through their existing boundaries. JDBC and those services use
the same data source and transaction manager; tests exercise real Spring transaction
proxies to check rollback, OTP attempts and refresh-reuse behavior.

## Startup and first dedicated staff lead

No real account, email delivery or live migration was performed during implementation.
Tests use synthetic data and mocked email/storage in a temporary local database.

1. Back up the development database. Stop the currently running backend before its
   next startup. **V58 changes listing visibility** as explicitly agreed.
2. Load your usual private backend environment, including the existing Gmail SMTP,
   OTP HMAC, JWT and database settings. Never paste passwords or keys into chat.
3. Start once with the extra bootstrap profile, using the dedicated staff email you
   control. This profile is an operator action, not a public API:

```bash
cd /home/hp/Desktop/makao-hub-backend
SPRING_PROFILES_ACTIVE=mock,staff-bootstrap \
STAFF_BOOTSTRAP_EMAIL='YOUR_DEDICATED_STAFF_EMAIL' \
./mvnw spring-boot:run
```

4. On first setup, the backend sends a one-use invitation code to that mailbox. No invitation code is
   printed or returned to a staff lead. If sending fails or acceptance is uncertain,
   do not repeatedly restart to send more emails: inspect the safe failure first.
5. Use the trusted staff client/Postman to call `POST /api/v1/staff/auth/accept-invitation`.
   Send `email`, `invitationCode`, `fullName`, `password` in the JSON body, **not a URL**.
   Choose a new staff email: an existing customer account is not promoted. No tokens
   are returned from acceptance; the response says to sign in with email OTP.
6. Stop and restart with only your usual profile, for example `mock`. Remove
   `staff-bootstrap` after setup. Accidentally leaving it enabled no longer crashes
   startup: existing staff produce `STAFF_EXISTS`, and an unexpired unused invitation
   produces `INVITATION_PENDING`. Both skip creation and email. Use the invitation
   already sent, or sign in if it has been accepted. An ordinary restart does not resend.
   For a missing first invitation, use the explicit operator resend described below.
7. Sign in at `/api/v1/staff/auth/login`, then complete `/verify-otp`. Use the resulting
   Bearer token for staff APIs. The first lead can invite a verification reviewer,
   safety moderator and payment reviewer with their separate permissions.

The bootstrap email is the only new operator setting. No new SMS/API key is required.
Use TLS before exposing staff credentials/documents beyond the local test host.

### Restart correction (19 October 2026)

Scope: fix the startup failure `409 Staff bootstrap has already been initialized`.
Assumption: an existing invitation or staff account must be preserved. No database
migration, account promotion, invitation cancellation or automatic resend is needed.
Existing invitation lifetime and dedicated-email requirements stay in force. This
uses Java 21, Spring Boot 4.1.1 and the existing Spring transaction/JDBC APIs.

The changed files are:

- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/StaffInvitationService.java`:
  `BootstrapResult` carries only one of three fixed setup outcomes to the startup
  runner. `bootstrap` returns the outcome rather than throwing for completed setup.
  Removing the early returns restores the restart failure and could permit duplicate
  invitation delivery if the checks were removed altogether.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/StaffBootstrap.java`:
  its fixed `Logger` and `run` switch translate the committed outcome into operator
  instructions. Without this switch, a skipped setup could be mistaken for a newly
  sent code. No recipient, code, credential or database exception is interpolated.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/StaffBootstrapTest.java`:
  mocked dependencies isolate setup behavior without real mail or account changes.
  `existingStaffSkipsBeforeCheckingRecipientOrSendingEmail` proves the lock precedes
  the existence check and that completed setup does not depend on a stale email.
  `pendingInvitationIsRetainedWithoutResendingOrChangingRecipient` prevents startup
  from replacing a pending invitation. `emptySetupSendsOneInvitationWithExistingSecurityPolicy`
  checks first setup's one delivery and audited STAFF_LEAD scope.
  `publicAccountStillCannotBePromoted` retains the privilege-escalation guard.
  `unavailableDatabaseIsNotTreatedAsCompletedSetup` and `smtpFailureIsNotReportedAsSent`
  ensure actual failures propagate. `startupRunnerAcceptsEverySuccessfulSetupState`
  exercises every runner branch. Removing these tests loses regression coverage for
  the exact startup failure and its security boundaries.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminWorkspaceIntegrationTest.java`:
  `setup` races two first-setup calls through real transactional proxies and checks
  exactly one invitation and one mocked email. It then accepts that invitation using
  the existing MFA flow. `restartingBootstrapAfterAcceptanceDoesNotAddInvitationsOrStaff`
  proves a restart preserves stored account/invitation counts and sends no new email.
  These checks are necessary because mocked tests cannot prove database locking.
- `/home/hp/Desktop/makao-hub-backend/docs/admin-backend.md`:
  startup instructions and this walkthrough document the changed return contract.
  Without the update, the operator would still expect setup to abort on every restart.

Security-critical sequence in `bootstrap`, in source order:

1. `@Transactional` keeps the lock, checks, and any new invitation/audit in one
   transaction. Failures roll back database writes.
2. `managementLock()` takes the existing PostgreSQL transaction advisory lock before
   reading setup state. Concurrent startup callers cannot both create the first lead.
3. Any `staff_accounts` row returns `STAFF_EXISTS`, including disabled accounts.
   Restart cannot replace disabled staff with a new privileged account.
4. An unexpired unused invitation returns `INVITATION_PENDING`. Changing the configured
   email does not redirect the pending invitation or trigger another delivery.
5. Only empty setup reaches the existing `issue` method, which validates the email,
   refuses public-account promotion, generates/stores code digests, sends mail, and
   audits the new invitation. Existing expired invitations are retained as before.
6. `INVITATION_SENT` is returned only after that call succeeds. The runner receives
   it after the service proxy commits; SMTP submission does not prove inbox delivery.

No HTTP contract, public role permissions or payment behavior changes. SMTP/database
failures and invalid first-setup configuration remain errors; they are not caught and
reported as success. Missing invitation email remains an operator recovery issue:
restarts do not recover or reveal the raw code stored only as a digest.

Verification uses a fresh localhost PostgreSQL database, all 58 migrations and mocked
mail. It must not target the Supabase development database. After verification, normal
startup is `SPRING_PROFILES_ACTIVE=mock ./mvnw spring-boot:run`. Check the pending
mailbox for the existing invitation or use staff sign-in if already accepted.
No new Safaricom/legal decision is required for this restart fix; email delivery and
invitation recovery remain the provider-related follow-up.

Result: 24 targeted tests passed (7 bootstrap unit tests, 3 HTTP authorization tests,
14 staff workspace integration tests), with zero failures or skipped tests. The
integration run applied V1–V58 on PostgreSQL 15.19 and exercised parallel first setup
and repeat setup after acceptance. Maven ran offline with an explicit Mockito 5.23.0
test agent because this execution environment blocks dynamic agent attachment.
No real Gmail delivery or Supabase account data was touched during verification.

### Explicit first-invitation resend

The owner requested a replacement invitation. This reuses the existing SMTP client
and the original bootstrap recipient; it does not provide a public resend endpoint.
No migration or account promotion is added. **RECOMMENDED testing default:** a
60-second database-backed cooldown prevents accidental repeated delivery.

To use it manually, stop the current backend, load the private settings with exported
variables, and run once:

```bash
cd /home/hp/Desktop/makao-hub-backend
set -a
source /home/hp/.config/makao-hub/backend.env
set +a
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export SPRING_PROFILES_ACTIVE='mock,staff-bootstrap'
./mvnw spring-boot:run -Dspring-boot.run.arguments=--resend-staff-invitation
```

`STAFF_BOOTSTRAP_EMAIL` must be the original dedicated recipient. After the send,
restart normally with `SPRING_PROFILES_ACTIVE=mock` and omit the resend flag. An
SMTP timeout can be ambiguous: check the inbox before another deliberate attempt.

Changed files and methods, all under `/home/hp/Desktop/makao-hub-backend/`:

- `src/main/java/com/makaohub/backend/admin/StaffInvitationService.java`:
  `resendBootstrap` performs the restricted replacement transaction; without it the
  initial operator has no way to recover an undelivered code. `BootstrapResult` adds
  `INVITATION_RESENT` so the caller distinguishes replacement from first setup.
  `invitationCode` centralizes the existing 32-random-byte generator for both first
  setup and resend, clearing its byte buffer in `finally`. `issue` uses that helper
  without changing its validation or grants. `accept` takes the management lock first,
  serializing acceptance with setup/resend; removing it would weaken that ordering.
- `src/main/java/com/makaohub/backend/admin/StaffBootstrap.java`:
  `run` selects resend only when `--resend-staff-invitation` is explicitly present and
  the existing operator-only profile is active. The new result logs fixed instructions
  without codes or recipient data. Removing the flag check would resend on restarts.
- `src/test/java/com/makaohub/backend/admin/StaffBootstrapTest.java`:
  adds checks for existing-staff refusal, original-recipient matching, public-account
  refusal, invalid input, cooldown, scoped rotation/audit, and explicit-flag routing.
  Removing those checks loses focused proof of the operator boundary.
- `src/test/java/com/makaohub/backend/admin/StaffInvitationResendIntegrationTest.java`:
  `resendIsAtomicSerializedAndCannotReuseOldCodesOrAcceptedInvitations` runs real
  transactions on a new localhost-only schema with mocked email. It proves missing
  and wrong recipients are refused; SMTP failure rolls back token/expiry and audit;
  two concurrent requests send once; old-code acceptance fails; new-code acceptance
  succeeds once; and existing staff prevent further first-lead invitation sends.
  It includes migration of a fresh database. Without it, mocks cannot prove locking,
  rollback, replay prevention or acceptance behavior.
- `docs/admin-backend.md`: documents command usage, changed code and delivery limits.

Security-critical source sequence in `resendBootstrap`:

1. The existing transaction advisory lock is acquired before checking any staff row.
   Any existing staff account, even disabled, blocks operator reprovisioning.
2. The original recipient is validated, normalized and checked against public accounts;
   this prevents privilege elevation of existing customers.
3. A parameterized, row-locked query requires the original email HMAC, STAFF_LEAD
   scope, operator origin (`invited_by IS NULL`), and no previous acceptance. Expired
   initial invitations may be renewed; another person's invitation cannot be redirected.
4. Audit timestamps enforce the 60-second cooldown across processes. No raw email is
   used as a log or rate-limit key.
5. The existing row's code digest is rotated and its expiry renewed to 24 hours.
   Only the new code can match; the raw code is never persisted or returned.
6. Existing authenticated STARTTLS SMTP delivers the message, then a fixed-reason
   `STAFF_INVITATION_RESENT` event is appended in the same database transaction.
   A mail exception rolls back the rotation. The operator reports success only after
   commit. SMTP acceptance still does not prove inbox delivery.

The one-time send requested in chat can use an isolated operator process that constructs
these same service, JDBC transaction and mail components from `application.yaml` and
the private environment. It does not start a second web server or scheduled jobs.
Temporary operator code contains no credentials or raw invitation code, and output is
limited to fixed result/error states. It is not a new application endpoint.

Verification result: 32 targeted tests passed (14 bootstrap unit, 3 HTTP security,
14 staff workspace integration, and 1 complete resend integration scenario), with
zero failures or skipped tests. Fresh local schemas applied all 58 migrations.
The owner-requested live operator send then returned committed `INVITATION_RESENT`:
SMTP accepted the message and the rotation/audit committed. Inbox delivery is not
established by that result; use the newest received invitation to verify acceptance.

Remaining risk: a successful SMTP send followed by a failed database commit can leave
an unusable email in the inbox; there is no durable delivery outbox or automatic retry.
Next manual check: receive the newest invitation, accept it once, then complete staff
password/email-MFA sign-in. No Safaricom or legal rule changed; production delivery,
recovery and retention policy still need confirmation with the mail provider/owner.

## API contract

### Invitation acceptance error reporting

An invitation is bound to its exact normalized recipient address. Gmail aliases
containing a `+suffix` may reach the same inbox, but are separate Makao Hub account
addresses. Enter the address shown on the invitation's **To** field, including that
suffix, with the newest code. The backend must not collapse these aliases because
that would weaken the dedicated staff-account boundary.

The website already renders a backend ProblemDetail's `detail`. Previously an
unmatched invitation raised an unhandled `ResponseStatusException`; the container's
error dispatch to the protected `/error` path could replace the intended 404 with
an empty 401. The website then displayed its generic fallback. A diagnostic request
with synthetic invalid invitation details reproduced the empty 401 on the running
backend. No real invitation was accepted or new code sent during that check.

- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/StaffAuthController.java`:
  `accept` retains request validation and delegates acceptance to the same transactional
  service. It now returns `ResponseEntity` explicitly. Expected status exceptions
  become ProblemDetail responses with fixed, non-sensitive guidance. This avoids
  `sendError`/container redispatch, preserves the original status, and never returns
  exception details or account identifiers. The 404 message covers wrong address,
  wrong/expired/consumed code together; it does not identify which secret matched.
  The 409 message directs existing staff to sign in. Other expected errors receive
  fixed validation/rate-limit/service guidance. Without this handling the useful
  error is lost; no authentication rule is relaxed to expose `/error`.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/StaffInvitationAcceptanceHttpTest.java`:
  `unmatchedInvitationReturnsStructuredGuidanceWithoutErrorRedispatch` proves a
  public invitation failure returns 404 and safe JSON without a servlet error message.
  `conflictDoesNotExposePrivateExceptionDetails` rejects exception-content leakage.
  `successfulAcceptanceKeepsTheExistingSignInRequiredContract` proves acceptance
  still returns no tokens and leaves MFA sign-in necessary.
  `invalidFormIsRejectedBeforeCallingTheInvitationService` retains form validation.
  Removing these checks loses coverage of reporting, privacy and pre-MFA behavior.

No schema, email-matching, invitation validity or permission changes are required.
Existing Play Framework 3.0.12 handling can display the corrected JSON without a
website code/layout change. Backend changes use the existing Java 21/Spring Boot
4.1.1 libraries. Restart the backend after compilation to load the new handler, then
retry in the browser using the exact invited address. Use normal `mock` startup;
do not resend simply to correct an email typo. Delivery/provider/legal rules are
unchanged. The remaining manual check is successful user-driven acceptance and MFA;
the developer should never request or enter the owner's password or invitation code.

All protected staff requests require a valid existing access JWT **and** a registered,
live staff access proof. An `ADMIN` JWT from a normal auth flow does not supply that
proof. Staff authentication responses preserve `AuthTokensResponse`; OTP challenges
preserve `challengeId`, `purpose`, `maskedEmail`, `expiresAt`.

| Method/path | Body or query | Permission/result |
| --- | --- | --- |
| POST `/api/v1/staff/auth/accept-invitation` | email, invitationCode, fullName, password | One-use invite, no authenticated session |
| POST `/api/v1/staff/auth/login` | Existing `LoginRequest` | Password validated, sends existing email OTP |
| POST `/api/v1/staff/auth/verify-otp` | challengeId, code | Staff-login challenge only; creates MFA session and token pair |
| POST `/api/v1/staff/auth/refresh` | refreshToken | Same MFA session, existing refresh-family rotation/reuse detection |
| POST `/api/v1/staff/auth/logout` | Bearer token | Revokes that staff session/family |
| POST `/api/v1/staff/auth/forgot-password` | email | Neutral response; existing email recovery |
| POST `/api/v1/staff/auth/reset-password` | email, code, newPassword | Existing recovery validation; sign-in required afterward |
| GET `/api/v1/admin/me` | — | Own staff job/document permission |
| GET `/api/v1/admin/verifications` | role optional, state=PENDING, search optional, page=0, size=20 | Lead metadata or reviewer's own assigned cases; oldest first |
| POST `/api/v1/admin/verifications/{role}/{user}/assign` | reviewerId, reason | Staff lead; active document reviewer |
| GET `/api/v1/admin/verifications/{role}/{user}` | — | Assigned reviewer; case/current document metadata only |
| GET `/api/v1/admin/verifications/{role}/{user}/documents/{document}` | — | Assigned document reviewer; audited inline bytes, no signed evidence URL |
| POST `…/documents/{document}/review` | — | Acknowledge previously viewed current evidence |
| POST `/api/v1/admin/verifications/{role}/{user}/decision` | approve, expectedUpdatedAt, reason | Pending case; all required current documents reviewed before approval |
| GET `/api/v1/admin/listings` | kind=LANDLORD/SUBLESSOR, state=PENDING, page, size | Safety moderation queue |
| GET `/api/v1/admin/listings/{kind}/{id}` | — | Confirmed media and listing content; exact coordinates omitted |
| POST `/api/v1/admin/listings/{kind}/{id}/decision` | approve, expectedUpdatedAt, reason | Safety moderator; explicit approval or correction request |
| GET `/api/v1/admin/flagged-messages` | page, size | Masked content from both conversation types |
| POST `/api/v1/admin/flagged-messages/{kind}/{id}/decision` | escalate, reason; kind=RENTAL/ROOMMATE | Safety warning or escalation; masking/FLAGGED state retained |
| GET `/api/v1/admin/reports` | state=OPEN, page, size | Safety reports; reporter identity omitted from queue |
| POST `/api/v1/admin/reports/{id}/decision` | state, priority, pauseListing, notifyUsers, reason | Assigned safety reviewer; terminal resolution cannot reopen |
| GET `/api/v1/admin/payments` | page, size | Read-only amounts/status; no checkout secrets |
| GET `/api/v1/admin/bookings` | page, size | Read-only rent/deposit/commission and release timestamps |
| GET `/api/v1/admin/audit` | actor/subject UUID optional, page, size | Staff lead; audited access |
| POST `/api/v1/admin/audit/export` | actor/subject optional, page, size, reason | Staff lead; bounded JSON page, audited/idempotent |
| GET `/api/v1/admin/activity` | page, size | Own events only |
| GET `/api/v1/admin/staff` | page, size | Staff lead directory |
| POST `/api/v1/admin/staff/invitations` | email, role, documentReview, reason | Staff lead; invitation sent to recipient |
| POST `/api/v1/admin/staff/{id}/permissions` | role, documentReview, enabled, reason | Staff lead; revokes target sessions/tokens |

Admin mutation/export endpoints in this table require `Idempotency-Key` containing
a UUID, except auth endpoints. A replay uses the original safe response; reusing a
key for different input returns 409. Queue results are `{items,page,size,hasNext}`.
SQL-backed item field names are the column names shown by the API, e.g. `user_id`,
`moderation_status`, `updated_at`. Send the last returned `updated_at` as ISO
`expectedUpdatedAt` when deciding; stale input returns 409, never overwrites a newer
decision. Pages are 1–50 items; page numbers are 0–10000. No unbounded export exists.

Decision reasons are required for both outcomes, maximum 500 characters. Do not copy
ID numbers, contact details or document contents into reasons. The backend rejects
common unsafe patterns; this is not perfect PII detection. The web client must render
all names/reasons/descriptions as text, never as HTML.

### Owner/renter integration

For both `/api/v1/landlord/listings/{id}` and `/api/v1/sublessor/listings/{id}`:

- POST `/submit-review`: owner and role checked; requires role verification, private
  location and confirmed photo; puts current draft/paused listing into the queue.
- GET `/review`: owner-only moderation status, reason and timestamps.
- POST `/revise-review`: owner opens a safety-paused/rejected listing as a draft.
  Existing draft-edit/media/location routes can then correct it and submit again.

Normal approve leaves a new listing as a draft until the owner publishes. Previously
published listings paused specifically by V58 can resume visibility on approval.
Rejecting a submitted listing opens it as a hidden draft for corrections. Archiving
a listing prevents a pending approval from restoring it. No listings, bookmarks,
applications, payments or entitlements are deleted; paid/booking views that require a
published listing may be unavailable while it is hidden.

POST `/api/v1/reports` accepts `subjectKind`, `subjectId`, `category`, `description`.
Allowed kinds: LANDLORD_LISTING, SHARED_LISTING, RENTAL_MESSAGE, ROOMMATE_MESSAGE.
Allowed categories: MISLEADING_CONTENT, PAYMENT_REQUEST, UNSAFE_CONTENT, OTHER.
An active public-role account may report a currently published listing or a message
from a thread it participates in. Reports store masked text, not identity evidence.
Safety states: OPEN → INVESTIGATING/ESCALATED/RESOLVED; RESOLVED is terminal. The
first acting moderator claims the report; another moderator cannot silently take it.
Notifications are fixed safe in-app summaries; hosts never receive reporter identity.
File attachments are **not supported** by this report API.

## Code explanation: files, classes and methods

All paths below begin `/home/hp/Desktop/makao-hub-backend/`.

### New module: `src/main/java/com/makaohub/backend/admin/`

- **`StaffRole.java`**: separates the four staff jobs from account role ADMIN.
  Nested `Permission` names the operation groups; `permits` is a fixed, constant-size
  allowlist. Without it, an admin role alone would imply excessive access.
- **`AdminRequests.java`**: nested `Invite`, `Accept`, `StaffChange`, `Assign`,
  `Decision`, `MessageAction`, `Report`, `ReportAction`, `AuditExport` records define
  exactly the bodies above, with validation/length limits. `Accept.toString` redacts
  its credentials. Removing validation allows malformed or oversized bodies; roles
  and permissions are still independently checked against the server's current data.
- **`AdminStore.java`**: constructor retains the transaction-aware JdbcTemplate and
  configures ISO Instant serialization for safe command responses. `update`/`execute`
  run parameterized/module-owned SQL through methods (not fields on Spring proxies).
  `rows` normalizes database timestamps; `one` produces a fixed 404 on absence;
  `exists` performs boolean checks; `page` bounds query size and reads one extra row
  for `hasNext`; nested `Page` is the queue contract. `audit` inserts server-dated
  metadata only. `denied` uses a separate transaction so denied actions survive the
  rejected business transaction. `command` locks an actor/key, compares fingerprints,
  replays a saved response or runs/stores one transaction; `digest` supplies SHA-256.
  `bad`/`conflict` return fixed validation/conflict responses. Removing these boundaries
  loses pagination, consistent failure handling, replay protection or audit integrity.
- **`StaffAccessService.java`**: `require(jwt)` checks exact account role ADMIN,
  authorization version, enabled staff, active account, access-token hash proof,
  nonrevoked/unexpired MFA session and a live refresh family. `require(jwt,permission)`
  additionally checks the job allowlist. `requireAssignment` locks/checks reviewer
  assignment and document permission. `allows` adapts this check to HTTP security;
  `denied` constructs a fixed 403. Nested `Staff` returns only own permission metadata.
  Removing any check permits pre-MFA, revoked, wrong-job or unassigned evidence access.
- **`StaffAuthService.java`**: `login` validates credentials before a separate OTP/
  marker transaction; this keeps failed-password auditing out of a rolled-back
  credential transaction. `verify` accepts only a marker produced by staff password
  sign-in, reuses OTP verification, flushes the existing auth persistence context and
  creates a family-bound staff session/proof. `refresh` locks account before session,
  reuses rotation and grants a new access proof without extending MFA duration.
  `logout` revokes proof scope/session/family. `recover` uses a neutral email recovery
  response; `reset` reuses existing code verification/password reset and audits it.
  `activeStaff` checks the stored assignment; `proof` stores only an access-token hash;
  `invalid` avoids leaking credentials. Removing these links would let normal ADMIN
  tokens impersonate a staff MFA session or break replay/revocation behavior.
- **`StaffInvitationService.java`**: `invite` checks staff-lead permission and command
  idempotency. `issue` validates dedicated email/scope, generates a random 32-byte
  invitation, persists only digests, sends by existing SMTP, then audits. `accept`
  locks a live invite, checks its email HMAC and inviter's continuing authority,
  refuses an existing account, creates password-hashed ADMIN/staff records from the
  stored invitation scope, consumes it, and returns no tokens. `change` cannot alter
  the lead's own scope; it changes another staff member and revokes their sessions.
  `bootstrap` and `managementLock` serialize first-lead setup/management. `BootstrapResult`
  distinguishes a newly submitted invitation from an existing invitation or staff account,
  so an ordinary restart does not become a startup exception. `emailDigest`
  domain-separates invitation-email HMAC from existing OTP use. Removing these checks
  enables public privilege grants, duplicate acceptance, secret leakage or stale access.
- **`StaffBootstrap.java`**: constructor takes invitations and the operator's email;
  `run` only executes under the explicitly enabled `staff-bootstrap` profile and logs
  fixed instructions for the returned state, after the service transaction completes.
  No mailbox, invitation code or password is logged. Without
  it there is no first-lead provisioning path; exposing it as HTTP would be unsafe.
- **`AdminWorkflowService.java`**: constructor connects JDBC authorization, private
  storage and existing contact masking. `verificationQueue` limits reviewer metadata
  to assigned cases; `assign` authorizes the lead and target reviewer;
  `verificationCase` returns document metadata, not object paths; `document` delivers
  owned/current READY evidence with a committed view record; `markDocumentReviewed`
  acknowledges viewed current bytes; `decideVerification` locks pending state and
  requires every role-specific document's current review before approval. Removing
  the view/review/version checks permits blind approvals or stale-document decisions.
  `assignedReviewer` enforces owner → assignment → evidence lock order, shared with
  reassignment/upload operations, preventing case reassignment racing a decision.
  `EvidenceSource`/`evidence` allowlist the three document tables/owner columns.
  Nested `Document` carries private bytes and MIME to the controlled response.
  `listings`/`listingDetail` provide queues/media without exact coordinates;
  `decideListing` rejects stale/unsubmitted/archived decisions and checks completeness;
  `submitListing`, `ownListingReview`, `reviseListing` enforce the owner's source role
  and ownership. `requireCompleteListing` requires verified active owner, location,
  confirmed photo. `ListingSource`/`listing` allowlist both source schemas. Without
  these, unrelated owners or incomplete listings could enter or leave moderation.
  `flags` reads and re-masks both message sources; `flagAction` records warn/escalate
  and fixed notifications, never unmasks a message; `messageTable` is its allowlist.
  `payments`/`bookings` select safe read-only columns; `staff` provides the lead's
  directory; `audit`/`activity` scope history; `export` returns a bounded replay-safe
  JSON page. `notifyUser` sends only safe existing in-app notifications; `reason`
  bounds/guards notes; `checkVersion` prevents lost decisions; `forbidden` audits scope
  denial. Without these methods the corresponding screen/action has no backend.
- **`UserReportService.java`**: constructor connects authorization/masking/notifications.
  `create` locks an active reporter, verifies publication/thread participation, limits
  requests and stores masked text. `queue` omits reporter identity. `act` serializes
  the assigned moderator, enforces terminal state, optionally pauses a listing with
  a separate audit event, and sends generic notifications. Removing these checks
  permits unrelated-message reports, identity leaks, lost decisions or unaudited pauses.
- **`StaffAuthController.java`**: constructor connects staff auth/invitations; `login`,
  `verify`, `refresh`, `accept`, `logout`, `forgot`, `reset` delegate the matching
  routes above. No business permission is decided from a client role in this controller.
- **`AdminController.java`**: constructor connects the permissioned services. `me`,
  `verificationQueue`, `assign`, `verification`, `document`, `reviewed`,
  `verificationDecision`, `listingQueue`, `listing`, `listingDecision`, `flags`,
  `flag`, `reports`, `report`, `payments`, `bookings`, `audit`, `activity`, `export`,
  `staff`, `invite`, `permissions` expose exactly the corresponding table routes.
  `document` adds no-store/private, inline filename, nosniff and sandbox headers;
  `export` adds no-store. Removing delegates removes the route, not the service's
  authorization. Browser rendering cannot technically prevent an authorized viewer
  saving/copying evidence; inline delivery is not a DRM/no-download guarantee.
- **`ModerationSubmissionController.java`**: constructor connects owner/report services.
  `landlord`/`sublessor` submit review; `landlordReview`/`sublessorReview` return owned
  state; `landlordRevise`/`sublessorRevise` open correction drafts; `report` accepts
  text safety reports. Removing these prevents the clients from completing moderation.

### Existing files changed

- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/auth/config/SecurityConfig.java`:
  `securityFilterChain` permits only the named staff authentication POST routes;
  admin routes additionally require live staff proof; admin payment POSTs are denied.
  Missing guard beans fail closed in sliced test contexts. Existing public role gates
  and Bearer/CSRF policy are retained. Removing this exposes legacy role-only admin routes.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/auth/config/AuthRateLimitFilter.java`:
  adds staff login/OTP/invitation/refresh/recovery and report limits to its existing
  map. No new limiter library or client-controlled forwarding header is introduced.
  Removing the entries drops pre-authentication rate limiting for those routes.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/auth/service/EmailOtpClient.java`:
  `sendCode` reuses extracted `deliver`; new `sendStaffInvitation` builds a validated
  plain-text, token-free-URL message. `deliver` retains STARTTLS/credential validation,
  sanitized errors and no automatic SMTP retry. Removing it prevents invitations;
  weakening it can leak codes/recipient data or silently duplicate delivery.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/notification/domain/NotificationType.java`:
  adds role/listing review, moderation warning and report outcome labels to the existing
  in-app contract. Without them existing notification mapping cannot read those rows.
- `/home/hp/Desktop/makao-hub-backend/src/main/resources/db/migration/V58__create_staff_workspace.sql`:
  new unapplied migration; no prior applied migration was edited. It creates the
  tables/indexes/constraints, RLS with no browser policies, append-only audit triggers,
  listing review metadata and notification labels. It audits/pauses old published rows
  with a restore-after-review marker. `enforce_listing_approval` blocks unapproved or
  unverified publication; `invalidate_listing_evidence_approval` resets approval and
  pauses on photo/location/household changes; `pause_listings_when_role_unverified`
  hides listings when role verification stops being verified. Removing the triggers
  allows publish/resume or evidence-edit bypasses outside a particular service.

## Security-critical transition walkthrough

1. A public role/normal ADMIN access token has no staff access-proof row: HTTP denies
   it before a queue is invoked. Proof hashes are generated from server-issued tokens,
   not client-supplied staff/MFA claims.
2. Staff credential validation uses the existing dummy-password protection. Only an
   enabled stored ADMIN/staff assignment reaches OTP sending and receives a staff
   challenge marker. No tokens are returned from password verification alone.
3. OTP verification checks the marker and reuses the HMAC-bound, expiry/attempt-limited
   challenge. Failed attempts commit; provider/configuration failures never grant a
   proof. A successful one-use code links a new proof to its refresh-token family.
4. Every protected staff request rechecks current account, version, enabled state,
   access proof, MFA/session expiry and live refresh family. Service methods recheck
   job permission; private evidence also checks assigned reviewer/document permission.
   Transactional reads lock staff authority during a business action.
5. Owner locks serialize upload/replacement, assignment and evidence decisions.
   Evidence delivery is bounded by the existing storage reader; paths remain server-
   side. Approval checks READY document IDs and matching update timestamps against
   that same reviewer's acknowledged views. Replacement invalidates the old proof.
6. Decisions and their audit/notification/command response commit together. Actor UUID
   and timestamps come from the server. Old request versions fail; duplicate command
   keys serialize even on first use. Different payload under the same key fails.
7. A listing cannot become PUBLISHED unless the database sees APPROVED/current role
   verification. Related evidence edits reset approval and hide it again. Existing
   customer records are retained throughout. No client approval flag is accepted.
8. Message review never restores raw contact details; report notifications contain no
   reporter identity or submitted document contents. Financial review writes no money.

## Tests, complexity and deployment limits

Verification: **218 tests passed**, zero failures/errors/skips, including 58 clean
Flyway migrations, V57→V58 upgrade with an existing published listing, actual local
Tesseract synthetic-image tests, and new HTTP/security/JDBC integration tests.
`MakaoHubBackendApplicationTests` was explicitly excluded because it depends on the
private runtime environment. This is not a live Gmail/Supabase/browser end-to-end test.

- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminWorkspaceIntegrationTest.java`:
  setup uses a dedicated local `makao_role_test` database and Spring transaction
  proxies. Helpers generate only synthetic accounts/tokens/images. Tests cover missing
  MFA and wrong jobs, existing-account invitation refusal and one-use acceptance,
  five failed OTPs, failed-password auditing, assigned/current document review,
  refresh replay/logout, revocation, publication guards, concurrent command replay,
  image/location invalidation for both listing sources, report privacy/resolution,
  append-only audit and bounded replay-safe exports. Removing these loses executable
  proof of those security boundaries; they send no real email and move no money.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminSecurityBoundaryTest.java`:
  three MockMvc checks prove anonymous/role-only rejection and block legacy admin
  financial POSTs even with an active staff session.
- Existing `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/property/service/RenterHomeIntegrationTest.java`
  setup now explicitly seeds approved synthetic listings after location setup; its
  owner-reverification test explicitly resumes a fixture afterward. Existing
  `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/roommate/service/SharedHomeIntegrationTest.java`
  household test seeds its publication approval explicitly. Neither weakens the guard.

Staff permission checks use a fixed enum (constant-size checks); required evidence
uses a HashSet, O(d) work for the small set of role documents. Database lookups have
primary-key/status/time indexes. Offset paging is O(offset + returned rows), **not**
constant time: deep pages are bounded; **RECOMMENDED** use keyset cursors if this grows
large. Auth IP limits remain per-instance and reset on restart/cache eviction; database
OTP limits/attempt counters remain authoritative. Shared limits are needed for replicas.

Libraries: existing Java 21, Spring Boot 4.1.1, PostgreSQL 15 integration tests,
Spring JDBC/Security versions managed by the existing parent. No new dependency was
added. Official references: [Spring JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html)
and [Spring request authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html).

## Staff invitation HTTP failure correction

`AdminController.rejected(ResponseStatusException)` now returns safe structured
errors directly, preserving the rejection status rather than calling servlet
`sendError` and potentially redispatching through the protected error route.
Only two fixed invitation/self-permission reasons are passed through; arbitrary
exception reasons are never exposed. Permission checks, MFA, account separation,
mail handling and database rules remain unchanged.

The connected website also now gives invitation SMTP requests the existing longer
auth timeout, distinguishes transport timeout from connection failure, and preserves
temporary refresh failure without falsely declaring the session expired. Full
file/method explanations, security walkthrough and regression checks are in
`/home/hp/Desktop/makao-hub-web/docs/implementation-notes.md`, section
"Staff invitation failure correction". No migration or data change was made.

## Verification acknowledgement precondition

The assigned reviewer's case response now includes existing `viewed_at` metadata
for each READY, current-version evidence item. The website uses it only to disable
premature Mark reviewed actions and explain the view/return/refresh sequence. The
backend's current-document check remains authoritative, and view, acknowledgement
and final decision remain separate audited actions. Fixed verification conflicts
now retain their safe instructions rather than all using the generic 409 message.
No live case, permission or migration was changed.

File/method explanations, security walkthrough and test scope are in
`/home/hp/Desktop/makao-hub-web/docs/implementation-notes.md`, section
"Verification acknowledgement correction".

## Remaining risks, what to test, confirmations and next task

- **Not production-ready:** email OTP is the agreed developer-testing method, not a
  phishing-resistant staff factor. Review production MFA/recovery before deployment.
- Mail can be delayed or accepted despite a timeout. No automatic retry is added;
  invitation creation/SMTP is not a durable delivery outbox. The first-lead resend
  above is an explicit operator action; a general staff delivery-status/recovery
  workflow and retention/cleanup jobs remain a follow-up.
- No new KMS/envelope encryption, deployment TLS/CORS/cookie design, separate database
  operator/app roles, or audit retention policy was chosen. The existing account-email
  schema does not prove application-layer encryption. Database-owner/superuser DDL can
  defeat audit triggers: least-privilege operational roles remain mandatory before production.
- Private evidence is delivered only to permissioned assigned staff, but cannot be made
  impossible for an authorized human to copy. Staff training, retention, document access
  policy and processing obligations need review with a Kenyan privacy lawyer.
- OCR is still a content heuristic, not authoritative identity/face matching. Admin
  approval does not establish a government-registry check or automatic face match.
- Reports currently accept text only and route supported listing/message subjects.
  Profile reports, private attachments, bulk exports, general staff invitation delivery recovery and
  a complete financial dispute/refund workflow are not claimed as implemented.
- Next manual tests: first staff invitation/acceptance using a real dedicated mailbox,
  password/OTP/login/recovery, separate jobs and denied actions, one assigned real
  document review with consent, old/new listing approval, and owner correction/resubmit.
  Use only test data/funds. No Safaricom/provider money behavior changed here; financial
  rules and the regulated custody arrangement still require explicit provider/legal review.
- **RECOMMENDED next task:** provision the first test staff lead, then build/connect the
  staff website and Android listing review controls to the documented APIs. Do not turn
  screenshot dummy refunds or redacted placeholders into unaudited financial actions.

## Landlord decision propagation regression proof

No backend production method or database contract changed. The existing
`AdminWorkflowService.decideVerification` remains the single writer for a final
role decision: it requires an assigned verification reviewer with MFA, locks and
version-checks the pending role row, stores `reviewed_at`, reviewer and rejection
reason, writes one audit event, and creates one applicant notification. The
existing landlord-owned verification GET reads that same role-specific record.

`/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminWorkspaceIntegrationTest.java`
adds `rejectedLandlordDecisionStoresTheOwnersOutcomeAndNotifiesOnlyOnce`. It creates
only synthetic data, assigns the existing test reviewer, rejects a pending
Landlord with an explicit reason, replays the same idempotency key, and proves the
owner-visible row has `REJECTED`, a review time and the same reason while exactly
one notification and one decision audit exist. Removing this test loses the
end-to-end guarantee required by the Android outcome refresh and replay safety.

Security sequence: assignment and MFA are checked before the decision; the row is
locked and compared with the expected update time; only a pending role can move;
the reason is stored server-side; notification and audit are created in the same
transaction; command idempotency makes a retry return the saved result rather than
duplicating side effects. A per-document `Mark reviewed` operation remains separate
and cannot update role status.

Risks: mail/push notification delivery is not the authority and the notification
channel is TBC; clients must read the saved role state. Provider outages delay a
refresh but must never synthesize approval. Rejection-reason contents, retention
and reviewer access require Kenyan privacy-law review. No M-Pesa/Safaricom or
regulated-custody rule changed. Suggested next task: verify the same owner-visible
decision contract for Sublessor and Renter without changing their role-specific
evidence requirements.

## Rejected listing revision correction

The database approval trigger remains unchanged and continues to reject publication
unless the current listing version is `APPROVED`. The Android app now uses the
existing submit/review/revise endpoints; the backend change here corrects one
inconsistent revision precondition and does not change data or approval rules.

- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/admin/AdminWorkflowService.java`,
  `reviseListing`: locks only an owned `REJECTED` listing whose status is `DRAFT`
  or `PAUSED`, resets it to an unsubmitted editable draft, and audits the actual
  previous state. `DRAFT` is required because a normal staff rejection already
  moves a newly submitted listing there; `PAUSED` remains allowed for an existing
  publication hidden by a safety report. Removing either case breaks its existing
  owner correction path. Role equality and ownership remain server-side.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminWorkspaceIntegrationTest.java`,
  `rejectedNewListingCanReturnToAnEditableUnsubmittedDraft`: creates a synthetic
  complete draft, submits it, records an authorised rejection, proves the saved
  rejected state, then proves only the owner can start a clean unsubmitted revision.
  The existing report-resolution test retains coverage of the paused case.

Security sequence: submit locks and checks the owned record; moderation decision
uses staff permission and optimistic version checks; rejection remains audited;
revision again checks JWT role, ownership, allowed status and `REJECTED`; the record
returns to `DRAFT/PENDING` with no submission timestamp; later publication still
requires a separate current approval and current role verification at the database.

Remaining risks: clients installed before this correction can still attempt the old
direct publish and receive a safe constraint conflict. Review notification delivery
is not an authority and its channel remains TBC. Test with synthetic listings before
real property data. Moderation reason retention/disclosure needs Kenyan legal review;
no Safaricom, payment, refund or regulated-custody behavior changed. Suggested next
task: decide whether the owner listing collection should include a small moderation
summary to reduce per-listing status reads without exposing staff information.
