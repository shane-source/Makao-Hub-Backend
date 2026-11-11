# Google sign-in for an existing email/password account

## Requirement and assumptions

An existing Makao account must be usable through Google when the selected Google
account has the same email. Keep the same user ID, password, name, role and role
verification state. Do not create a second account or merge account records.
Email means the same address, case-insensitively; do not guess Gmail dot/plus aliases.
No new identity provider, dependency, payment policy or public-admin access is added.

The existing Google verifier checks RS256 signature, issuer, configured audience,
expiry and `email_verified`. Google says third-party addresses may have changed
ownership even when `email_verified` is true, and recommends an additional ownership
challenge: https://developers.google.com/identity/gsi/web/guides/verify-google-id-token.
We reuse the current email OTP for all Google logins rather than weakening it for
particular domains. Libraries remain Java 21 / Spring Boot 4.1.1 and its managed
Spring Security/JPA dependencies; no library API or authentication setting changes.

## Contract, state and failure modes

`POST /api/v1/auth/google` still accepts `idToken` and `expectedRole`, and returns
only the existing `OtpChallengeResponse` with purpose `LOGIN`. Android already
opens its OTP screen and calls `POST /api/v1/auth/google/verify-otp`; no client
change is necessary. Both OTP verification routes use the same server-bound
challenge; neither accepts a Google subject or account ID from the client.

An unlinked Google subject and an existing matching email create a pending LOGIN
challenge, not a Google identity or session. Only a correct, unexpired, unused
code links the subject and consumes the challenge in the token-issuing transaction.
Existing linked subjects continue resolving by subject even if their email changes.
Unknown emails still require registration. Disabled accounts, incorrect roles,
conflicting subjects, provider failures and invalid codes grant no session.

## Changed files and method walkthrough

### `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/auth/api/AuthController.java`

`authenticateWithGoogle` still rejects absent/ADMIN public role selections first.
It delegates to `GoogleAuthenticationService.startLogin` instead of requiring an
already-stored Google identity. This is the shared Android/web endpoint; reverting
it would leave existing password accounts unable to reach inbox verification.

### `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/auth/service/GoogleAuthenticationService.java`

The constructor adds the existing user repository and OTP service so the shared
entry point can resolve an account and challenge it without issuing tokens.

Security-critical `startLogin`, in execution order:

1. `verifyGoogleToken` calls the unchanged server verifier before any account
   lookup. Its JWT-error mapping returns a generic credential failure instead of
   exposing decoder internals. Removing verification permits forged identities.
2. Lookup the immutable Google subject first. This preserves existing bindings;
   email changes must not move a Google identity to another Makao account.
3. Only when no binding exists, lock and find the candidate by the verified token's
   email, case-insensitively. No user-provided email is accepted here. This lookup
   is not ownership proof and does not save a binding. No matching user means the
   existing `GOOGLE_REGISTRATION_REQUIRED` result, not automatic registration.
4. `requireActiveRole` checks active account state, then calls the existing
   server-role resolver. A client-selected role can reject a login, never grant
   a different role or role verification. Removing this permits disabled-account
   login or privilege confusion.
5. `startGoogleLogin` passes the verified subject into the inbox challenge.
   There is no direct token issuance or identity creation on this path.

`verifyIdentity` now reuses `verifyGoogleToken` and `requireActiveRole`, preserving
its linked-subject-only behavior and its legacy `authenticate` caller. It does
not get the email fallback: that would bypass inbox ownership proof on a direct
token-issuing path. No production controller calls that legacy method.

### `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/auth/service/AuthOtpService.java`

The constructor receives `GoogleIdentityRepository`. Removing it prevents checking
and persisting bindings inside the same OTP transaction.

`startGoogleLogin` rejects missing/blank/overlong subjects, then calls the shared
`issue` overload with LOGIN and the verified subject. Null must not silently turn
this into a password-only challenge. The original `issue(user,purpose)` delegates
with no subject, preserving password registration/login/reset behavior.

The subject-aware `issue` retains the existing user-first lock, active/current
authorization checks, three-send quota, random six-digit code, five-minute expiry,
SMTP error handling and previous-challenge supersession. It checks binding
availability under that user lock, binds the subject before calculating the digest,
then sends to the stored Makao inbox. SMTP failure does not create a binding.

`digest` adds a Google-subject suffix only for bound challenges. The existing
HMAC already includes challenge ID, user ID, purpose, authorization version,
stored email and code. Adding the subject prevents changing the proposed Google
identity after issuance. Keeping the old format when subject is null preserves
outstanding codes created before migration; no forced data rewrite is necessary.

Security-critical `verifyAndIssueTokens`, in execution order:

1. Resolve the challenge's stored owner; lock that user before the challenge.
   Consistent lock order serializes competing consumers and avoids lock inversion.
2. Check purpose, pending status, expiry, attempt limit, active/current account and
   authorization version. Old codes cannot survive an account/role security change.
3. Compare the bound HMAC in constant time. Wrong codes persist failed-attempt counts
   through the existing `noRollbackFor=AuthException` policy; no identity is saved.
4. For a Google-bound challenge, call `ensureGoogleLinkAvailable` again. It rejects
   an existing different subject on this user or this subject on a different user.
   Issuance-time checks alone are insufficient because state can change in between.
5. If this user has no identity, `saveAndFlush(new GoogleIdentity(...))` saves it
   under the held user lock. Existing database primary-key/unique-subject constraints
   remain the final protection against duplicate or reassigned bindings.
6. Mark the challenge verified and issue tokens through the unchanged token service,
   which reloads the stored role. A downstream runtime failure rolls back the link
   and OTP consumption together. A replay cannot issue a second session.

`ensureGoogleLinkAvailable` never replaces a binding and returns a generic error
directing the user to their existing sign-in method. Removing it risks account
takeover or silently replacing a user's Google identity.

### `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/auth/domain/AuthOtpChallenge.java`

`pendingGoogleSubject` is nullable and insert-only in JPA. `bindGoogleSubject` accepts
it once, only on an undigested pending LOGIN challenge; null, blank and oversized
values are rejected. `getPendingGoogleSubject` exposes the binding only to the
service, not the API response. Without these, an OTP could not securely carry the
proposed identity until ownership is proven. It stores no raw ID token or OTP.

### `/home/hp/Desktop/makao-hub-backend/src/main/resources/db/migration/V66__bind_google_account_link_to_email_otp.sql`

Adds the nullable subject column and a database constraint allowing it only for
LOGIN and nonblank values. No existing user, password, identity, OTP digest or
role is changed. Removing V66 makes persistence fail on the new mapped column;
removing the constraint permits invalid registration/reset bindings in the database.
Previous applied migrations are unchanged.

## Tests and supporting documentation

- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/auth/service/GoogleAuthenticationServiceTest.java`:
  its constructor receives mocked repositories/OTP. The new password-account test
  proves candidate lookup starts only OTP; the rejection test covers invalid tokens,
  unknown accounts, suspension and wrong roles; the linked-subject test proves
  subject precedence and that direct authentication cannot use the email fallback.
  Removing these leaves the original login regression and bypass boundary unchecked.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/auth/service/EmailOtpClientTest.java`:
  `prepare` supplies the new dependency; `googleChallenge` sets up a synthetic bound
  challenge. Added tests cover absent/malformed subjects, no link/session before a
  valid inbox code, unchanged password, replay denial, conflicting accounts,
  rechecking conflicts at consumption, subject tampering, expiry and existing links.
  Removing them loses proof of the link-before-session and immutable-binding gates.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/auth/api/RegistrationContractTest.java`:
  the role-mismatch and successful-challenge tests now stub/verify `startLogin`.
  They preserve the public-role and OTP-only HTTP contracts. Without updating them,
  mocks would exercise the obsolete controller delegation rather than real routing.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/auth/service/RoleMigrationIntegrationTest.java`:
  existing constructor call sites receive the new dependencies. The new all-role
  test exercises actual repositories/migrations for renter, landlord and sublessor,
  checks failed-attempt persistence, races two consumers (one winner), proves stable
  account/password/role and subject uniqueness. The rollback test proves session
  failure undoes the binding and OTP consumption, and rejects non-LOGIN binding in
  PostgreSQL. Removing these leaves transaction/locking/constraint behavior untested.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/admin/AdminWorkspaceIntegrationTest.java`:
  `setup` supplies the Google repository to the shared OTP constructor.
  No staff behavior changes. Without this wiring the admin regression suite fails
  to compile; public Google linking still cannot select ADMIN.
- `/home/hp/Desktop/makao-hub-backend/docs/google-signup.md` points existing-account
  users to sign-in rather than duplicate signup; signup's rejection remains intact.
- This file records the design, security ordering, verification and limitations;
  removing it loses the rationale for the extra inbox ownership proof.

## How to verify

Validation on 3 November 2026: compilation succeeded; 335 tests passed, zero
failures/errors, and one unrelated OCR test was skipped because
`TESSERACT_TEST_PATH` is absent. The run exercised all 66 migrations and the 14
role/auth database integration tests, including the two new Google-link tests.
The credential-dependent application startup test (`*Tests`, rather than `*Test`)
is not selected by this script. No real Google/SMTP/device login was performed.

Run `bash docs/run-role-tests.sh` from `/home/hp/Desktop/makao-hub-backend` to use
synthetic data in a temporary local PostgreSQL cluster, never Supabase. It applies
all migrations on fresh schemas. No real Google credential or SMTP delivery is
used; the independently validated identity and mail boundaries are mocked.

Restart the backend normally to apply V66. On Android, choose the account's current
role, open Sign in, choose Continue with Google, select the same Google email, and
enter the emailed code. Expect the existing profile/listings/applications, not a new
account. Sign out and verify password login still works, then Google login again.
Repeat for renter, landlord and sublessor. A different Google email should not gain
access, and selecting the wrong role must still fail.

## Remaining risks and next task

Actual Google/SMTP/device sign-in remains a manual acceptance check; this change
cannot fix an incorrectly configured Google audience, signing certificate or email
provider outage. Existing bindings are deliberately not merged or overwritten.
Existing broader authentication audit/retention policy is unchanged; RECOMMENDED:
review dedicated append-only authentication/linking audit coverage and retention
before production. Do not log emails, subjects, codes or tokens while debugging.
Confirm Google OAuth configuration with Google/provider guidance; have privacy
counsel confirm federated-identity disclosure and retention obligations for Kenyan
users before production. No payment/custody arrangement changes are involved.
RECOMMENDED next task: complete the on-device acceptance sequence above.
