# Google signup

## Current behavior (3 November 2026)

Google signup now requires the number entered in the existing registration form.
Accounts created earlier without a number are prompted to complete it after sign-in.
See `/home/hp/Desktop/makao-hub-backend/docs/google-phone-required.md` for the
current contract, security walkthrough, test results and remaining limitations.
No missing number is fabricated or backfilled. Google-only password hashes and
optional avatar paths remain nullable by design.

## Historical phone-optional implementation (superseded)

The notes below explain V57 and older behavior, not the current signup contract.
V57 remains applied and unchanged to preserve existing accounts until their users
provide actual numbers. Do not edit that migration or run the old device steps below.

Normal signup retains its phone field and validation. Continue with Google ignores
that field. The selected role, Google token and consent still go to the backend;
email OTP and role verification still follow. A missing phone is never fabricated.

Existing email/password accounts can now use **Sign in → Continue with Google**.
The matching Google account is linked only after the existing inbox OTP succeeds;
Google registration still rejects duplicate accounts. See
`/home/hp/Desktop/makao-hub-backend/docs/google-account-linking.md` for that flow.

## Files, methods and connections

- `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/ui/AuthScreens.kt`:
  `RegisterScreen` validates consent for Google and calls `googleRegister` without
  the normal form's phone. Normal `Create account` still validates every required
  form field. Without this separation, Google would still prompt for a phone.
- `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/AuthViewModel.kt`:
  `googleRegister` builds the phone-free request using the previously selected
  role, then opens the backend's OTP challenge. Removing it breaks signup routing;
  treating its response as a session would bypass the OTP gate.
- `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/data/MakaoApi.kt`:
  `GoogleRegisterRequest` omits phone serialization entirely; the normal
  `RegisterRequest` retains it. Removing this distinction risks sending unrelated
  form data into the Google signup path.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/auth/api/GoogleRegistrationRequest.java`:
  this record accepts absent/empty phone. A nonempty phone from an older client
  must still match the existing format. Name can be omitted for Google fallback;
  token, selected role and consent remain required. Without this contract change,
  HTTP validation would reject the Android request before the service runs.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/auth/service/GoogleRegistrationService.java`:
  `register` preserves the signup security sequence described below. Its optional
  phone branch skips encryption/lookup only when no number was supplied. Removing
  that branch would make phone entry mandatory again.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/auth/domain/User.java`:
  the Google-capable constructor accepts both phone protection values or neither;
  a partial pair is rejected. The password constructor still requires both.
  `getPhoneCiphertext` defensively copies present data and returns null otherwise.
  These changes represent an absent number without weakening normal registration
  or allowing partially protected phone data.
- `/home/hp/Desktop/makao-hub-backend/src/main/resources/db/migration/V57__allow_google_signup_without_phone.sql`:
  removes phone-column NOT NULL constraints and adds a paired-presence constraint.
  It preserves existing values and phone uniqueness, with no backfill or user-data
  rewrite. Without V57, persistence would reject new phone-free Google accounts.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/profile/service/ProfileService.java`:
  `getMyProfile` returns null for an absent phone after existing account/role
  checks; it decrypts only present data. Otherwise OTP success would be followed
  by a profile-loading exception for a new Google account.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/payment/service/DarajaPaymentProvider.java`
  and `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/payment/service/PaystackPaymentProvider.java`:
  their `start` methods reject missing payment phones with 409 before decryption
  or contacting a provider. This prevents null crashes or invalid charge requests.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/property/service/RenterListingPhoneAccessService.java`:
  after existing role, listing and entitlement checks, the phone read returns 409
  for an owner with no number instead of attempting to decrypt null.

## Security sequence inside GoogleRegistrationService.register

1. Reject public ADMIN creation before side effects.
2. Verify the ID token using the existing issuer/audience/expiry checks. The
   account email and subject come from the validated token, preventing client
   identity forgery. A Google display name does not prove legal identity.
3. Reject existing subjects or emails rather than silently linking accounts.
4. For an older client supplying a phone, retain normalization, encryption and
   uniqueness lookup. When absent, store neither ciphertext nor lookup hash.
5. Resolve the selected role and a usable supplied/Google name; reject invalid
   inputs before creating the account.
6. Save the user, role, Google identity and legal acceptance in one transaction.
7. Start the existing email OTP challenge. Signup alone grants neither a Makao
   session nor role verification approval; OTP verification still issues tokens.

## Regression checks

The Android `AuthContractTest.googleSignupOmitsPhoneAndKeepsSelectedRoleAndOtpGate`
checks the actual Retrofit payload and protected-profile denial before OTP.
Backend `RegistrationContractTest` checks the HTTP boundary: Google permits absent
phone/name, normal signup requires phone, and Google still requires consent.
`GoogleRegistrationServiceTest` checks name fallback, role persistence, OTP gating,
invalid tokens and rejection of automatic linking to an existing email.
`RoleMigrationIntegrationTest.phoneOptionalSchemaSupportsGoogleAccountsAndPreservesExistingPhones`
persists and reads two phone-free users, confirms an existing phone is unchanged,
and rejects a partial phone pair against PostgreSQL. `ProfileServiceTest` covers
profile loading without decryption when phone is absent. Both payment-provider
tests prove missing phone causes no external request. Removing these tests would
leave the corresponding signup, migration and provider regressions unchecked.

## Verify on the device

Restart the backend to apply V57, rebuild Android, choose a role, open Create
account, leave phone blank, accept consent and use Continue with Google. Expect
email OTP followed by role verification. Normal Create account with a blank phone
must still fail validation. Tests use synthetic users and an isolated local database.

A phone-free user still needs a payment number before real M-Pesa checkout. A
profile flow for adding one later is outside this change. No provider, escrow,
refund or custody policy changed. Provider/privacy terms require review before
production. RECOMMENDED next task: run this Google signup path on the device.

Validation: 189 backend tests passed, with all 57 migrations exercised on local
PostgreSQL, and 56 Android unit tests passed with a successful debug APK build.
The application startup smoke test requiring private runtime settings was excluded;
live Google sign-in and real email delivery still need the device check above.
This uses the project's Spring Boot 4.1.1, Kotlin 2.2.10 and Retrofit 3.0.0 versions,
with no new library dependency.
