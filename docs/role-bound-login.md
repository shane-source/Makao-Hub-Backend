# Role-bound sign-in

## Outcome and scope

Choosing Renter must not accept a Landlord or Sublessor account, even when its credentials are correct. The same rule applies in every direction, for password and Google sign-in. This is a login constraint, not a role switch.

The Android app previously omitted the selected role from both login requests. The backend correctly used the stored account role for tokens, but could not compare that role with the user's selection. The app then opened the workspace returned by the server. This explains acceptance of the wrong selection; it is not evidence that the old code minted Renter privileges for a Landlord.

No prices, payments, verification rules, role assignments or existing data change. There is no new migration or dependency. Existing dirty changes in both projects are preserved.

## Request and failure contract

Public password sign-in, `POST /api/v1/auth/login`:

```json
{"email":"synthetic@example.invalid","password":"synthetic-password","expectedRole":"RENTER"}
```

Public Google sign-in, `POST /api/v1/auth/google`:

```json
{"idToken":"synthetic-google-token","expectedRole":"RENTER"}
```

These are synthetic examples, not working credentials. Send credentials only in an HTTPS request body outside the existing local debug setup.

- `expectedRole` must be `RENTER`, `LANDLORD` or `SUBLESSOR`. Missing/null/unknown/array values and `ADMIN` are rejected with HTTP 400 on public login routes.
- Correct credentials plus a different stored role return HTTP 403 with `code: ROLE_MISMATCH`. No challenge, email or token is created by this rejected request. Existing sessions are not revoked by a failed login attempt.
- Wrong credentials retain their existing generic authentication error, regardless of the selected public role. A mismatch does not disclose the actual stored role.
- Correct credentials plus the matching stored role return the existing OTP challenge. OTP verification is still required; selecting a role does not bypass identity verification or grant permissions.
- Older public clients/Postman requests must now send `expectedRole`. Update Android and backend together. Staff sign-in continues using its existing separate staff route and email/password contract.
- Refresh and OTP JSON contracts are unchanged. Their authority still comes from the database and authorization version, not a client-supplied role.

## Security-critical guard, statement by statement

In `LegacyRoleSelectionService.resolveForLogin`:

1. `resolve(user, null)` refreshes and locks the persisted account using the existing account-row lock. It rechecks account availability and refuses an incomplete legacy role choice. Passing `null` here deliberately prevents this login selection from assigning a legacy role.
2. `if (expectedRole == null) return` preserves the internal/staff credential flow. Both public controllers reject null before calling authentication, so omitting the field on a public HTTP request cannot use this compatibility branch.
3. `findAllByUser_Id(user.getId())` loads assignments for the credential-verified account. The caller cannot choose another user's ID.
4. `assignments.size() != 1` fails closed for missing/ambiguous data. It does not choose the first role from an invalid multi-role account.
5. `getFirst().getRole().getName() != expectedRole` compares the single database role with the requested constraint. Equality of enum values is constant-time O(1); no role cache or matching heuristic is introduced. The repository uses the existing user-role lookup.
6. The mismatch exception stops the call before `AuthOtpService.startLogin` is evaluated. There is no role write, verification-state write or token issuance in this guard.

Password authentication runs this guard only after the password and account checks. Google authentication runs it only after verifying the Google token, resolving its stored identity and checking account status. Existing OTP digests bind the account's authorization version; a later authorization change invalidates the challenge before token issuance. Existing access-token validation and explicit role-switch revocation remain unchanged.

In Android `AuthRepository.verify`:

1. The existing flow selects the registration/password/Google OTP endpoint.
2. The response role is compared with the selected `SignupRole` before session installation.
3. On mismatch, any local session is cleared. The returned refresh-token family is revoked on a best-effort basis using the existing logout endpoint; no raw tokens are logged or persisted.
4. A cancellation is propagated. A revocation/network failure cannot cause local acceptance of the response. `LoginRoleMismatch` is still raised.
5. Only a matching response is installed under the existing session mutex. Server-side authorization remains necessary on every protected request; this client check cannot grant permissions.

## Changed production files, classes and methods

All backend paths below are under `/home/hp/Desktop/makao-hub-backend/`.

| File / class / method | What it does and connects to | Why; what breaks without it |
| --- | --- | --- |
| `src/main/java/com/makaohub/backend/auth/api/LoginRequest.java` — record and two-argument constructor | Adds `expectedRole` to password JSON. Keeps the existing two-argument internal/staff constructor delegating to a null constraint. | Carries the Android selection without changing the staff contract. Removing the component loses the selection; removing compatibility breaks existing internal callers. Public controllers still require it. |
| `src/main/java/com/makaohub/backend/auth/api/GoogleAuthRequest.java` — record | Adds the same enum constraint to Google login JSON. | Prevents leaving the Google path with the original omission. It does not use Google's profile or client input to assign authority. |
| `src/main/java/com/makaohub/backend/auth/api/AuthController.java` — `login`, `authenticateWithGoogle`, `requirePublicLoginRole` | Both public routes require a public selection. Password receives the full request; Google receives the token plus expected role. The private validator rejects null/ADMIN. | Without the validator an omitted constraint could use the internal compatibility path. Without forwarding, credentials would still be accepted for another selection. |
| `src/main/java/com/makaohub/backend/auth/service/LegacyRoleSelectionService.java` — `resolveForLogin` | Reuses the existing lock/status/legacy checks and compares the single stored role. | Shared check for both providers; removing it restores the mismatch. It never calls legacy role assignment with the login role. |
| `src/main/java/com/makaohub/backend/auth/service/PasswordAuthenticationService.java` — `verifyCredentials` | Invokes `resolveForLogin(user, request.expectedRole())` after valid credentials. Its existing `authenticate` wrapper inherits the check through this method. | Ensures no public password OTP is created for a mismatched role, and preserves generic wrong-password errors. |
| `src/main/java/com/makaohub/backend/auth/service/GoogleAuthenticationService.java` — both `verifyIdentity` overloads | The role-aware overload performs the existing identity checks then the shared guard. The original overload delegates with null for existing internal callers. | Protects HTTP Google login without breaking the existing internal token-issuance wrapper/tests. Public requests always use the role-aware overload. |
| `src/main/java/com/makaohub/backend/auth/exception/AuthException.java` — `Reason.ROLE_MISMATCH` | Gives the mismatch an allowlisted machine-readable reason. | Without a distinct reason the app cannot show a safe, actionable explanation. |
| `src/main/java/com/makaohub/backend/shared/error/ApiExceptionHandler.java` — `handleAuthException` | Maps that reason to HTTP 403; keeps existing errors unchanged. | Ensures consistent API handling without leaking an internal exception or the actual account role. |

All Android paths below are under `/home/hp/Desktop/makao-hub-frontend/`.

| File / class / method | What it does and connects to | Why; what breaks without it |
| --- | --- | --- |
| `app/src/main/java/com/makaohub/android/data/MakaoApi.kt` — `LoginRequest`, `GoogleRequest` constructors | Require a `SignupRole expectedRole`, serialized by existing Retrofit/Gson. | Every Android caller must provide the selection. `SignupRole` has no ADMIN value. |
| `app/src/main/java/com/makaohub/android/data/AuthRepository.kt` — `google` | Forwards its expected role into `GoogleRequest`. | Otherwise Google loses the selection before HTTP transmission. |
| Same file — `verify` | Requires the expected role, rejects/discards mismatched token responses and attempts logout before installing a matching session. | Defense against an outdated/misconfigured backend; removing it allows silent wrong-role session installation on that backend. |
| Same file — `LoginRoleMismatch`, `friendlyError` | Adds a local rejection type and a fixed user-facing explanation for local or server mismatch. | Keeps raw server/provider details out of the UI while telling the user to select their account's role. |
| `app/src/main/java/com/makaohub/android/AuthViewModel.kt` — constructor | Accepts an optional repository for testing; Android's default construction still uses the configured backend. | Lets navigation tests use local synthetic responses without email or real accounts. |
| Same file — `selectRole` | Clears an old challenge when the selection actually changes; still refuses changes during a request. | Prevents reusing a previous role selection's pending challenge in the UI. |
| Same file — `login`, `googleLogin` | Pass the selected role to the repository. Existing Google-new-account handling remains intact. | These were the missing connections from role selection to the API. |
| Same file — `verify`, `perform` | Verification passes the role constraint before opening Account. Local role rejection clears private state and returns to Login. The existing profile-loading/retry behavior remains unchanged. | Prevents accepting a wrong-role token response while retaining profile refresh after a transient profile-request failure. |
| `app/src/main/java/com/makaohub/android/ui/AuthScreens.kt` — `LoginScreen` | Existing introductory text now says which role is being used. | Makes the selected constraint visible; no new screen or field is added. |
| `app/src/main/java/com/makaohub/android/MainActivity.kt` — `MakaoApp` back routing | Login's Back action returns to role selection. | The user can correct the selection after a mismatch without restarting onboarding. |

## Tests and their connections

Backend test paths are under `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/`:

- `PasswordAuthenticationServiceTest.java`, `validCredentialsIssueTokens`: updates the existing interaction assertion to the shared login guard. The generic wrong-password test is retained. Without the assertion a future refactor could silently stop invoking role validation.
- `auth/service/GoogleAuthenticationServiceTest.java`, `storedGoogleAccountUsesServerSideRole`: verifies guard-before-token ordering with the internal compatibility overload. The invalid-Google-token test still proves credentials are checked first.
- `auth/api/RegistrationContractTest.java`: `publicLoginRequiresAPublicExpectedRoleForPasswordAndGoogle` checks invalid selections; `loginForwardsTheSelectionAndNeverIssuesOtpOnRoleMismatch` proves the API forwards the role and creates no challenge on rejection; `matchingPasswordAndGoogleLoginStillReturnOnlyAnOtpChallenge` preserves MFA; `eachPublicRoleIsDeniedTheOtherRolesWorkspaces` checks all six cross-role workspace denials through the security filter chain. Removing these tests loses the HTTP/authorization regression checks; it does not remove runtime authorization.
- `auth/service/LegacyRoleSelectionServiceTest.java`: `expectedLoginRoleCannotCompleteALegacyRoleChoice` protects the separation from legacy migration; `loginFailsClosedIfTheStoredRoleAssignmentIsMissingOrAmbiguous` protects invalid-data handling.
- `auth/service/RoleMigrationIntegrationTest.java`: `passwordAndGoogleLoginOnlyAcceptTheStoredRoleWithoutMutatingIt` runs both real authentication services and the real guard/repositories against PostgreSQL for all public stored roles and all expected roles, including ADMIN rejection by mismatch. It checks wrong-password privacy, unchanged roles/version/verification and absence of OTP/refresh rows. Google identity verification is synthetic, not a live provider call. The existing `emailOtpFailureCountsPersistAndConcurrentConsumptionHasOneWinner` test additionally checks that a later authorization-version change makes a pending OTP unusable. Without these tests mocks alone could hide a database/locking integration error.

Android test paths are under `/home/hp/Desktop/makao-hub-frontend/app/src/test/java/com/makaohub/android/`:

- `AuthContractTest.kt`: updates existing request-shape/session tests to supply the role. Adds `passwordAndGoogleLoginSendEachSelectedRoleAndRejectMismatches`, `wrongRoleOtpResponseIsDiscardedAndRevokedForEveryFlow`, and `wrongRoleTokensStayDiscardedWhenServerRevocationFails`. These exercise real Retrofit JSON requests and the local session gate against a local test server; none send emails.
- `LoginRoleFlowTest.kt`: setup/response/idle/cleanup helpers create the local server and test dispatcher, await completion and release resources. `passwordAndGoogleRoleMismatchNeverOpenOtpOrDashboard` checks every selected public role; `matchingRoleOpensItsWorkspaceAfterOtp` checks successful routing and pending verification; `oldServerWrongRoleTokensReturnToSignInWithoutProfileRequest` checks the compatibility defense; `selectingAnotherRoleInvalidatesThePreviousOtpChallenge` checks challenge clearing. Removing this file loses navigation regression coverage.
- `LandlordVerificationFlowTest.kt` setup, `LandlordContractTest.kt` session helper, `SublessorContractTest.kt` login helper and `RenterContractTest.kt` login helper now state their synthetic session's expected role. This keeps existing workspace authorization and verification tests compiling under the required repository contract; test tokens cannot silently bypass the new gate.

This notes file has no runtime behavior; it records the changed API, security reasoning and verification instructions so the boundary is not later mistaken for an automatic role switch.

## Verification and remaining limits

Backend validation uses Java 21 and the project's Spring Boot 4.1.1/Maven dependencies. Android uses its existing Kotlin 2.2.10, Retrofit 3.0.0, AGP 9.4.0 and Gradle 9.6.0. These are repository versions, not new library recommendations. No library API or version change is needed.

Run backend unit/HTTP tests plus the existing isolated PostgreSQL integration suite. Never point the tests at Supabase/customer data; the integration test only accepts the dedicated local `makao_role_test` database. Existing migrations are exercised on a fresh temporary database, not applied to the running application database. The private-runtime `MakaoHubBackendApplicationTests` context test is excluded; OCR's real-engine check is conditional on a local test engine and unrelated to this change.

Android checks: `./gradlew --offline -Pkotlin.compiler.execution.strategy=in-process testDebugUnitTest assembleDebug`. The in-process option avoids writing a separate Kotlin daemon cache outside the granted directories; it need not change project configuration.

Verified locally on 19 October 2026: the backend suite completed 225 tests, with 224 passing, zero failures/errors and one real-engine OCR test skipped because `TESSERACT_TEST_PATH` was not configured. The private-runtime application-context test was excluded. Existing migrations ran successfully on the temporary PostgreSQL database; it was stopped after the run. Android completed 63 unit/contract/navigation tests with zero failures or skips, and assembled the debug APK. No real OTP was sent, no app was installed on the phone, and the running backend/live database was not restarted or migrated.

Manual check after restarting the backend and rebuilding/running Android Studio:

1. Select Renter and sign in with a Landlord account. Expect the role-mismatch message and no OTP.
2. Press Back, select Landlord, and sign in with that account. Expect email OTP, then its Landlord verification state/dashboard, not the Renter workspace.
3. Repeat the mismatch and correct-selection checks with a Sublessor account and Google sign-in.
4. Confirm a wrong password still gets the generic authentication error and that public ADMIN selection cannot authenticate staff.

Risks remaining: an already installed app/running backend keeps its old behavior until updated. A failed best-effort logout can leave a rejected response's refresh family on an old server until expiry; Android still discards it and the updated server prevents the mismatch before issuance. This change does not replace TLS, ownership checks, server-side role enforcement or verification. It does not claim production readiness or change the unresolved KMS/payment-custody choices.

Provider/lawyer confirmation: none newly required for this login comparison. Existing identity-document, privacy and regulated-payment decisions remain separate and unresolved where previously noted.

RECOMMENDED next task: verify both role-selection flows on the user's phone after rebuilding; then resume the staff workspace work. Do not install onto the phone or send real OTPs automatically.
