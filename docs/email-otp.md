# Backend-generated email OTP and eight-character password minimum

14 October 2026. Implementation is complete and tested with mocked delivery; actual Gmail delivery needs private configuration and a deliberate real-inbox test.

## What changed and what did not

The owner chose real email and Gmail for developer testing, and a password minimum of eight instead of twelve. The existing authentication pipeline now generates its own codes and uses authenticated SMTP only for delivery. The Ponytail skill favored replacing the existing client/configuration rather than adding another auth system or provider SDK. Spring Boot's managed mail starter is the sole added dependency.

Active Infobip client/configuration/test files and the obsolete SMS setup guide were replaced. There is no Twilio, Resend or Infobip OTP fallback. Applied database migrations, old accounts, encrypted phone/location fields, JWT signing keys, JPA, payment rules and role permissions were preserved. We did not read/edit the private environment file or revoke external credentials. Remove unused INFOBIP_*, TWILIO_* and RESEND_* entries privately and revoke unused provider keys separately. Never replace your existing encryption or JWT keys merely to set up email.

Email OTP proves access to the account email during the operation. It does not prove phone ownership, approve identity documents, verify a Landlord/Sublessor role, authorize payouts or replace stronger Admin MFA.

## Gmail setup

This uses SMTP with required STARTTLS, not unencrypted SMTP or implicit-TLS port 465. Google's documented SMTP server is smtp.gmail.com on port 587 for STARTTLS. Use the full sender Gmail address as the username. [Google SMTP settings](https://support.google.com/mail/answer/7104828?hl=en-en).

1. Enable 2-Step Verification on the sending Google account.
2. Open [Google App Passwords](https://myaccount.google.com/apppasswords), create an app password for Makao Hub, and store it privately. Do not use your ordinary Gmail password. App passwords require 2-Step Verification and may be unavailable for certain managed/security-key-only/Advanced Protection accounts. [Google's app-password instructions](https://support.google.com/accounts/answer/185833).
3. Set these variables in your existing private backend environment file. All placeholder values below must be replaced, not copied unchanged.

```bash
export SMTP_HOST='smtp.gmail.com'
export SMTP_PORT='587'
export SMTP_USERNAME='YOUR_GMAIL_ADDRESS'
export SMTP_PASSWORD='your-google-app-password'
export OTP_FROM_EMAIL='YOUR_GMAIL_ADDRESS'
export OTP_HMAC_KEY='your-random-base64-key'
```

Generate OTP_HMAC_KEY once with `openssl rand -base64 32`, paste the result privately, and keep it stable across restarts and replicas. This is a separate secret for protecting stored codes, not a Gmail password/API key or an OTP. Changing it invalidates outstanding challenges. Never paste passwords, keys or received codes into chat or commit them. RECOMMENDED: a dedicated development sender and a secrets manager before deployment.

Restart after loading the environment, retaining the mock payment profile:

```bash
cd /home/hp/Desktop/makao-hub-backend
source /home/hp/.config/makao-hub/backend.env
export SPRING_PROFILES_ACTIVE=mock
./mvnw spring-boot:run
```

Install the rebuilt APK at /home/hp/Desktop/makao-hub-frontend/app/build/outputs/apk/debug/app-debug.apk. Old Android builds expect maskedPhone and need updating alongside the backend. The OTP arrives in the email entered for the Makao account, which need not be the sender's address. Check that inbox and Spam. Email acceptance does not guarantee arrival, and Gmail limits/anti-abuse restrictions still apply; this is not unlimited sending.

## API contract and state transitions

Existing request paths and verify bodies are unchanged. Signup selects one role before registration; ordinary login supplies no role.

- Email/Google registration returns REGISTRATION challenge, not an authenticated session. Verify via POST /api/v1/auth/register/verify-otp.
- Password login returns LOGIN challenge; POST /api/v1/auth/login/verify-otp consumes it.
- Google login uses the existing Google verification route and LOGIN purpose.
- Verify requests contain challengeId and the six-digit code; never a client-supplied account role or email.
- Forgot-password accepts the account email and returns neutral 202 text. POST /api/v1/auth/forgot-password/reset accepts email, code, newPassword.
- The challenge response contains challengeId, purpose, maskedEmail and expiresAt. No plaintext code, digest, provider ID, password or premature token is returned.

Challenge state: PENDING -> VERIFIED once; five failed attempts -> LOCKED; expiry or replacement -> EXPIRED. TTL is five minutes. Issuance keeps the existing limit of three accepted challenges per account/purpose in fifteen minutes, plus endpoint/IP rate limits. Delivery errors do not grant a session or invalidate the preceding pending challenge. There is no automatic SMTP retry or fake resend button.

## Security-critical sequence, statement by statement

### Issuing a code

1. Reject a non-active/missing account; acquire the current user write lock. That lock serializes concurrent sends for the same account.
2. Reject stale authorization versions, restricted accounts and unresolved role selection. A cached caller cannot send using outdated authorization.
3. Count recent same-purpose challenges before contacting SMTP. This constrains mailbox flooding without trusting Android throttling.
4. SecureRandom selects from one million values. ROOT-locale formatting preserves six digits including leading zeroes, rather than using a predictable counter or language-specific numerals.
5. Construct a UUID challenge bound to the current user, purpose, authorization version, issue time and expiry.
6. Compute HMAC-SHA256 over a versioned string containing challenge UUID, user UUID, purpose, authorization version, account email and code. Persist only its 64-character hex digest. A database leak alone cannot enumerate six-digit codes without the separate HMAC key; copying a digest to another challenge fails.
7. Send to the stored account email, not an arbitrary client-supplied recipient. SMTP requires authentication, STARTTLS and certificate hostname verification. No debug logging is enabled.
8. Only after the sender accepts the mail, expire the previous pending same-purpose challenge and save the replacement. Return only masked email/challenge metadata.

SMTP delivery and a database commit cannot be one atomic transaction. A timeout may conceal acceptance, or a commit failure may leave an unusable email. No automatic resend was added because it can duplicate messages. SMTP timeouts bound blocking calls; the account lock remains held during this synchronous send.

### Checking a code

1. Resolve user ID with the existing scalar challenge lookup.
2. Lock user first, challenge second; consistent ordering avoids the user/challenge deadlock pattern.
3. Require the server-selected purpose, PENDING state, stored digest, remaining attempts and unexpired timestamp. Historical SMS-only rows have no digest and fail closed.
4. Check account ACTIVE, resolved role, matching user ID and unchanged authorization version. Switching roles/restricting an account invalidates prior challenges.
5. Require exactly six ASCII digits. Recompute the challenge-bound HMAC and compare decoded digest bytes with MessageDigest.isEqual, avoiding an ordinary early-exit string comparison.
6. A wrong code increments attempts and throws the generic invalid/expired error. noRollbackFor=AuthException preserves failed attempts rather than rolling them back.
7. On success mark VERIFIED while locked, then issue the token pair in the same transaction. A concurrent second consumer observes a used challenge.
8. Password reset additionally validates the new password before consuming the code, hashes it with the existing PasswordEncoder, increments authorization version through changePasswordHash and revokes all active refresh tokens. Reset codes cannot be used as login codes.

## Changed files, classes and methods

Backend paths below begin /home/hp/Desktop/makao-hub-backend/.

### src/main/java/com/makaohub/backend/auth/config/EmailOtpProperties.java

Replaces InfobipTwoFactorProperties. The record constructor validates a plain sender email and a Base64 key decoding to exactly 32 bytes. validEmail rejects whitespace/header injection and bounds length; fromEmail/hmacKey accessors feed the client/service; toString redacts both values. Removing it permits malformed configuration or insecure digest setup.

### src/main/java/com/makaohub/backend/auth/service/EmailOtpClient.java

Replaces InfobipOtpClient. The constructor receives Boot's JavaMailSender, MailProperties and EmailOtpProperties, validates required host/port/credentials, requires SMTP authentication/STARTTLS/hostname verification, rejects JNDI override, mail debug and custom certificate-trust overrides, then stores the sender/from address. sendCode checks a single email, six digits and purpose; selects purpose-specific wording, builds a plain-text SimpleMailMessage, and calls send once. Authentication failure maps to a fixed configuration error; other MailException failures map to a safe unavailable error without attaching sensitive causes. Without it codes cannot reach a real inbox; without its checks errors could disclose message content or delivery could downgrade to plaintext.

### src/main/java/com/makaohub/backend/auth/service/AuthOtpService.java

The constructor replaces the phone-decryption/provider dependencies with EmailOtpProperties and EmailOtpClient and creates the HMAC key. startLogin/startRegistration still select fixed purposes and call issue; verifyLogin/verifyRegistration still call the fixed-purpose verifyAndIssueTokens transaction. issue now generates a random code, assigns its digest, sends email and masks the email. digest constructs the challenge/user/purpose/version/email-bound HMAC using a fresh Mac instance per call (no shared mutable crypto object). verifyCodeOrFail now compares digests locally and keeps persistent attempts. resetPassword now rejects fewer than eight characters/blank input or more than 72 UTF-8 bytes, then retains the existing password-hash/session revocation sequence. requestPasswordReset only changed its outage comment; neutral responses and account lookup are retained. mask hides the complete local part. ensureUsable/ensureUserCurrent/invalidOtp/normalizeEmail retain the checks described above. Removing this service disconnects all signup/login/reset challenges; bypassing any binding/lock/expiry/attempt check permits reuse or unauthorized sessions.

### src/main/java/com/makaohub/backend/auth/domain/AuthOtpChallenge.java

Maps code_digest rather than provider_pin_id, using the existing V53 schema. setCodeDigest allows one 64-hex assignment only while pending. isUsable requires a digest plus the existing state/time/attempt conditions. getCodeDigest is for internal service checking, not API serialization. Constructor, recordFailedAttempt, markVerified, markExpired and supersede retain the state machine. Removing digest assignment/checking makes safe backend verification impossible. No applied migration was edited and no user data was rewritten.

### Password/API/error files

- src/main/java/com/makaohub/backend/auth/api/OtpChallengeResponse.java: record field maskedEmail replaces maskedPhone; without this contract change Android cannot show the right destination.
- src/main/java/com/makaohub/backend/auth/api/RegisterRequest.java and ResetPasswordRequest.java: @Size minimum becomes eight, maximum remains 72 characters. Controller validation rejects seven/73 before service work; service byte checks additionally prevent BCrypt truncation.
- src/main/java/com/makaohub/backend/auth/service/RegistrationService.java: register calls enforcePasswordLimits, which checks nonblank/eight minimum and the retained 72-byte ceiling before hashing. Registration still requires email OTP before tokens. Removing the guard permits internal callers to bypass DTO rules.
- src/main/java/com/makaohub/backend/auth/exception/RegistrationException.java: adds PASSWORD_TOO_SHORT, a safe service-level reason.
- src/main/java/com/makaohub/backend/shared/error/ApiExceptionHandler.java: handleRegistrationException maps that reason to 400; removing the case breaks the exhaustive enum switch/error contract.
- src/main/java/com/makaohub/backend/auth/exception/OtpProviderException.java: the existing Failure codes keep their identity but messages now say email/inbox; constructor still does not preserve sensitive provider causes.
- src/main/java/com/makaohub/backend/auth/service/GoogleRegistrationService.java: register's comment now accurately says shared email challenge, not phone verification; behavior routes through the same OTP service.

Eight is a minimum, not an exact length. No password hashes were decrypted/replaced; BCrypt remains in use and its cost is unchanged. Existing login passwords are not rewritten. RECOMMENDED: longer, unique passphrases for stronger security, especially before production.

### Configuration and dependency

- pom.xml: adds Boot-managed spring-boot-starter-mail 4.1.1; otherwise JavaMailSender auto-configuration/mail types are unavailable.
- src/main/resources/application.yaml: replaces Infobip settings with security.email-otp and spring.mail environment references. Authenticated STARTTLS is required, hostname checking is on, TLS protocols are 1.2/1.3, mail debugging is off, connect/read/write timeouts are bounded. Missing configuration fails closed; no credentials are embedded. [Spring Boot email configuration](https://docs.spring.io/spring-boot/reference/io/email.html), [Angus SMTP security properties](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html).
- V52/V53 historical migrations remain intact. provider_pin_id stays as an unused historical column rather than risking an applied migration rewrite or unapproved destructive data change.

### Android files

Paths begin /home/hp/Desktop/makao-hub-frontend/.

- app/src/main/java/com/makaohub/android/data/MakaoApi.kt: OtpChallenge reads maskedEmail. Retrofit routes/session handling are unchanged.
- app/src/main/java/com/makaohub/android/AuthViewModel.kt: forgot shows account-email delivery; passwordError checks nonblank/eight minimum and retained character/byte ceiling/matching confirmation. Both signup and reset share this helper; bypassing it allows bad forms to reach the backend (which still enforces its own rules).
- app/src/main/java/com/makaohub/android/ui/AuthScreens.kt: OtpScreen uses Verify your email, masked recipient/inbox/spam instructions and Verify email button; ResetScreen directs users to account email. No fake resend or returned-code display was introduced.
- app/src/main/java/com/makaohub/android/data/AuthRepository.kt: friendlyError keeps allowlisted stable codes and safe email wording. It never shows raw provider details. No client OTP generator/secret was added.
- IMPLEMENTATION_NOTES.md: current notice points here and supersedes older SMS/immediate-token history. Removing these synchronized changes leaves incompatible recipient parsing or misleading UI.

### Tests and verification

- Backend src/test/java/com/makaohub/backend/auth/service/EmailOtpClientTest.java replaces the Infobip test: constructor/configuration checks, injection/key rejection, SMTP payload and safe errors without retries. Nested ChallengeFlow setup captures generated mail codes using mocks; issue helper installs repository fixtures. Tests cover digest-only persistence, single use, five attempts, expiry/purpose, restricted/stale authorization, copied-digest rejection, failed-send preservation, quotas/legacy-row rejection, reset session revocation, eight/seven/oversize boundaries and neutral reset responses.
- Backend src/test/java/com/makaohub/backend/auth/domain/AuthOtpChallengeTest.java: state-machine fixtures now assign synthetic digests, retaining lock/replay assertions.
- Backend src/test/java/com/makaohub/backend/auth/api/RegistrationContractTest.java: expects maskedEmail/no premature token and tests signup/reset eight/seven/73 boundaries. Dedicated synthetic IPs isolate these validation cases without disabling production rate limits; existing role/namespace tests remain.
- Backend src/test/java/com/makaohub/backend/auth/service/RoleMigrationIntegrationTest.java: emailOtpFailureCountsPersistAndConcurrentConsumptionHasOneWinner uses real local PostgreSQL/JPA transactions with mocked SMTP, checks digest-only storage, committed failed attempts, exactly one concurrent consumer, unchanged role-verification status and the database digest constraint. All existing role migration/ownership checks remain.
- Android app/src/test/java/com/makaohub/android/AuthContractTest.kt: Retrofit fixture/parsing uses maskedEmail; validates eight/seven/blank/73/UTF-8 byte boundaries and safe email errors/session denial.

Completed: all 118 backend tests passed with a fresh temporary local PostgreSQL instance, synthetic credentials and mocked mail; all 53 migrations and startup/JPA validation passed. The temporary database server was stopped. All 22 Android tests passed and debug APK assembly succeeded. git diff --check passed. Old compiled OTP-provider classes were removed by a clean build. No real email/SMS, live Supabase operation or private-credential edit occurred.

After private Gmail setup, verify one real email login, wrong code, correct code, logout/re-login and forgot/reset. Reused/expired codes must fail; signup must not access profile before successful OTP; password reset must invalidate old sessions. Enter received codes in the app, never chat/logs.

## Remaining risks and next task

Gmail credentials/eligibility, deliverability, spam classification and quotas are external and untested here. Missing or rejected credentials still produce safe errors; software cannot promise every email arrives. A personal Gmail sender is for developer testing, not a production transactional-mail readiness claim. This OTP change is not a complete security audit; Admin MFA, production transport, provider data-processing/retention and wider deployment requirements remain separate. Confirm provider terms/privacy handling and Kenyan data-protection obligations with the provider/qualified counsel before production; this task changes no escrow/custody rules. RECOMMENDED next task: privately configure the sender/app password, restart both sides, then run one controlled real-inbox test.
