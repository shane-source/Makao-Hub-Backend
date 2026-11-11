# Self-service account lifecycle (test build)

## Decided behavior

An active Renter, Landlord or Sublessor may request an email code for either deactivation or a deletion request. The email must match the authenticated account. A valid single-use code changes the account status. Deactivation is reversible: signing in with the existing credentials starts a separate reactivation code and confirmation. Deletion is **not erasure**: the code opens a staff-review case and disables sign-in. Staff must review retention and erasure rules before implementing actual deletion.

Both actions are blocked while the account is a party to an active booking, has an unresolved payment or pending test payout. An owner with a deactivated account has no public listing search/detail access; the published listing row is unchanged and becomes visible again on reactivation. Existing booking, payment, document and audit records are untouched.

## Contract and transitions

`POST /api/v1/profile/me/account-action/start` accepts `{email,action}` where `action` is `DEACTIVATE` or `DELETE`, and returns a five-minute email challenge. `POST /api/v1/profile/me/account-action/confirm` accepts `{email,action,challengeId,code}` and returns 204. Both require an authenticated public role; the JWT subject, not a client-supplied user ID, selects the account. `POST /api/v1/auth/reactivate/verify-otp` accepts the normal OTP-verification request and returns a token pair only after the reactivation challenge succeeds. The existing password/Google login endpoints issue a reactivation challenge for a deactivated account, not a session.

Legal transitions are `ACTIVE -> DEACTIVATED`, `DEACTIVATED -> ACTIVE`, and `ACTIVE -> DELETION_PENDING`. `SUSPENDED`, `DELETED` and `DELETION_PENDING` cannot self-reactivate. Every transition increments the authorization version and revokes refresh sessions (reactivation starts a fresh session). PostgreSQL records status transitions in the append-only `account_status_events` table. A deletion request remains visible to the existing staff review queue.

The code path locks the user, verifies the OTP purpose, owner, freshness, digest and attempt count, then rechecks account email and money blockers inside the same transaction before changing status. Wrong codes consume one of five attempts. A code for one action cannot authorize another; a consumed code cannot be replayed. The database migration only adds constraints and audit structures; it does not rewrite existing users. Renter discovery queries check owner account status at read time so no listing data migration is needed.

## Failure behavior and boundaries

Unresolved financial state returns 409; invalid account state/email is rejected; unavailable email service cannot silently complete an action. This flow makes **no Paystack transfer or refund**. Direct listing links also return unavailable while an owner is deactivated. Server-side checks remain the authority even if the Android UI is bypassed.

## Implementation map

The file and method boundaries are intentional; removing any of the guards below would allow a client action or old token to bypass the lifecycle rule.

| File / class | Methods and connection |
| --- | --- |
| `profile/api/AccountLifecycleController.java` | `start` and `confirm` validate the request and derive the user ID from the authenticated JWT. `purpose` maps only the two supported actions; `subject` rejects malformed identities. The controller delegates all state changes to the service. |
| `profile/service/AccountLifecycleService.java` | `start` checks the stored email and open money before sending a code. `confirm` consumes the purpose-bound code, rechecks both conditions in the same transaction, opens a deletion-review case when appropriate, changes status and revokes sessions. `requireNoOpenMoney` checks bookings, payment intents and TEST transfers; removing it could strand funds or a dispute. `requireActiveAndMatchingEmail` blocks cross-account and non-active requests. |
| `profile/service/AccountDeletionRequestService.java` and `profile/api/AccountDeletionRequestController.java` | `request` now runs only after code confirmation; `mine` and `pending` expose only owner/staff-scoped case reads. Direct unauthenticated or unconfirmed submission is not available. |
| `auth/service/AuthOtpService.java` | `startLogin`/`startGoogleLogin` issue a reactivation challenge for deactivated users. `startAccountAction` accepts only deactivation/deletion purposes. `verifyAccountAction` checks challenge ownership, purpose, expiry, digest and attempts without issuing a session. `verifyReactivation` consumes a separate challenge, restores ACTIVE and only then issues tokens. `issue` and `ensureUserCurrent` reject stale status/version. |
| `auth/service/PasswordAuthenticationService.java`, `GoogleAuthenticationService.java`, `LegacyRoleSelectionService.java` | Credential checks may identify a deactivated account and its stored role only for reactivation. They do not grant another role or a session. |
| `auth/domain/User.java`, `AccountStatus.java`, `AuthOtpChallenge.java`, `AuthOtpChallengePurpose.java` | Define permitted account states/transitions and OTP purpose/Google-subject binding. Version increments invalidate old JWTs; status/purpose binding prevents a login code being reused for deletion. |
| `auth/api/AuthController.java`, `auth/config/SecurityConfig.java`, `AuthRateLimitFilter.java`, `auth/service/EmailOtpClient.java` | Route the new verification endpoint; require public-role authentication for account actions; throttle code requests and checks; send purpose-specific email. Removing any one weakens the boundary between an anonymous login and an authenticated state change. |
| `property/repository/ListingRepository.java`, `property/service/RenterHomeService.java` | Public landlord queries and renter landlord/sublessor queries require an ACTIVE owner. This is evaluated on every read, not by mutating published listings. |
| `V73__self_service_account_states.sql` | Adds legal DB status/purpose values and append-only transition audit. `V72__account_deletion_requests.sql` adds the review queue without erasing data. |
| Android `data/MakaoApi.kt`, `data/AuthRepository.kt`, `AuthViewModel.kt`, `ui/AuthScreens.kt`, `ui/AccountLegalScreens.kt` | Connect the two-step account action and separate reactivation confirmation to the API, clear local tokens after confirmation, and display legal drafts and actions in all three roles. The UI never decides account status itself. |

Security-critical `confirm` order: (1) resolve the user from the signed JWT, not from request JSON; (2) lock that user's row and the challenge; (3) check the challenge belongs to that user and the exact action, has not expired or been consumed, and matches its HMAC digest; (4) consume a wrong-code attempt without issuing access; (5) recheck stored email and open financial state; (6) write the review request before `DELETION_PENDING`, or set `DEACTIVATED`; (7) increment authorization version and revoke refresh sessions; (8) commit status, audit row and challenge together. Reactivation uses the same challenge checks but requires `DEACTIVATED` and explicit user confirmation in Android before returning new tokens.

`RECOMMENDED`: provide a public web deletion-request page and approved policy text before Play publication. The present in-app Privacy Policy and Terms are explicitly drafts, not legal advice or approved commitments. `TBC`: review/erasure process, retention periods for identity and financial records, staff response channel, and any legal exceptions; confirm with Kenyan counsel and the payment provider. An `ACTIVE` booking currently has no completed status, so affected users cannot deactivate until that lifecycle is closed through an approved product rule.

## Verification

Run `./gradlew :app:testDebugUnitTest --offline` in the Android project and `bash docs/run-role-tests.sh` in this backend project. The latter migrates a disposable PostgreSQL database and covers authentication, authorization, OTP replay, money-state guards, and owner listing visibility. The migration and action flow should be checked against a staged copy of the real schema before deployment; never edit an applied Flyway migration.
