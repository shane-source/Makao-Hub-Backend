# Role migration and signup foundation

## Scope and decisions

This is the role-migration and profile-verification-status portion of Step 1, not the entire shared-profile implementation.

- JPA remains the persistence technology throughout the project, as explicitly confirmed by the owner.
- New users select one role BEFORE registration. Both registration DTOs continue to require `role`.
- Public roles are `LANDLORD`, `SUBLESSOR`, `RENTER`; public `ADMIN` registration remains rejected.
- Existing `ROOMMATE_SEEKER` users must explicitly choose `RENTER` or `SUBLESSOR`. No automatic mapping, matching opt-in or verification grant occurs.
- Existing account restrictions, identity, encrypted phone and password data are preserved.
- Existing landlord verification results belong only to the LANDLORD role and are preserved.

The screens show signup before role selection; the owner's subsequent answer explicitly selected role-before-signup. The API follows that answer. Payout encryption/KMS, cooling-off duration, verification providers, and payment rules remain undecided and are not implemented here.

## Versions and external references

Verified installed build: Java 21, Spring Boot 4.1.1, Hibernate ORM 7.4.5.Final, Mockito 5.23.0. PostgreSQL integration testing initially used 15.19. No dependency was added or changed.

Composite identifiers use JPA `@IdClass`. The record supplies matching identifier fields, equality and hashing; this was validated against the installed Hibernate implementation. See the [Hibernate guide on composite identifiers](https://docs.hibernate.org/orm/7.1/introduction/html_single/#composite-identifiers).

Database audit and initialization rules use [PostgreSQL triggers](https://www.postgresql.org/docs/15/trigger-definition.html). These are ordinary invoker-rights functions, not SECURITY DEFINER functions.

## Data model and migration

`V15__migrate_roles_with_explicit_legacy_choice.sql` is NEW. V1–V14 are unchanged.

1. Add `users.role_selection_required` and `users.authorization_version`. These are authentication state, not lifestyle, payment or verification data.
2. Mark only existing ROOMMATE_SEEKER accounts as requiring a choice and increment their token version. Suspended/deleted status is not changed.
3. Record a CHOICE_REQUIRED audit event and revoke those accounts' outstanding refresh tokens. Passwords and encrypted values are not read or rewritten.
4. Remove their old role assignment and the obsolete role definition. Add SUBLESSOR and update the allowed role constraint.
5. Rename `landlord_verifications` to `role_verifications`; add a role column and change the primary key to `(user_id, role)`. Existing rows receive LANDLORD, preserving their existing review fields and status. This is a storage change inside the verification module; its existing landlord upload/submission behavior is retained.
6. Initialize each currently assigned role's verification record if absent. New records default to NOT_STARTED. Existing review outcomes are never copied to another role or overwritten.
7. A trigger initializes verification after new role assignment. This covers both signup methods and legacy choices in the same transaction. `ON CONFLICT DO NOTHING` preserves an existing role-specific review.
8. A trigger validates completion of a legacy choice: an allowed role must exist and the authorization version must increase. It records ROLE_SELECTED in the same transaction. Rolling back login/token issuance also rolls back the choice and audit event.
9. Audit UPDATE, DELETE and TRUNCATE are rejected. Database owners can still disable triggers: this is not protection against a database administrator. Production needs a separate non-owner application role without DDL or audit-delete privileges; provisioning credentials is outside this change.

Migration requires a coordinated deployment with the old application stopped: old binaries cannot use the removed enum or renamed table. Take and verify a backup before applying it to real data. It has only been exercised against synthetic local data; no Supabase migration was run by the agent.

## API contract

New account, `POST /api/v1/auth/register`:

```json
{
  "fullName": "Test User",
  "email": "test@example.invalid",
  "phoneNumber": "+254712345678",
  "password": "TestOnlyPassword123!",
  "role": "SUBLESSOR",
  "termsAndPrivacyAccepted": true
}
```

The phone above is only a format example. Use a number you control for real registration. Do not trigger SMS or payments to example numbers. Google registration also requires a selected role and the existing profile/consent fields. Registration stores a NOT_STARTED verification record; registering is not identity verification.

An affected existing account authenticates through its usual login endpoint. Valid credentials without `selectedRole` produce HTTP 409, code `ROLE_SELECTION_REQUIRED`. Invalid credentials still fail before role selection. The frontend then asks the account owner to choose and resubmits credentials with the choice:

```json
{
  "email": "existing@example.invalid",
  "password": "the-existing-password",
  "selectedRole": "RENTER"
}
```

For `POST /api/v1/auth/google`, the body is `{"idToken":"<Google ID token>","selectedRole":"SUBLESSOR"}`. Google identity is resolved through its stored provider subject, not a client-supplied email or user ID. These endpoints retain their existing rate limits.

Only affected legacy accounts can use `selectedRole`. It is not a general role-switch endpoint. An unaffected account sending it gets 409 ROLE_SELECTION_NOT_REQUIRED; ADMIN/LANDLORD are invalid legacy choices and get 400 INVALID_ROLE_SELECTION. Suspended/deleted accounts cannot use it to restore access.

`GET /api/v1/profile/me` retains its existing fields and adds `verificationStatus` for the active `role`. `role` remains the JSON property name to preserve the existing contract. Missing verification evidence fails closed to NOT_STARTED. A VERIFIED LANDLORD record is never used to report another role as verified.

## Security-critical Java walkthrough

### LegacyRoleSelectionService.resolve

Location: `src/main/java/com/makaohub/backend/auth/service/LegacyRoleSelectionService.java`.

The password and Google authentication services call this only AFTER credential validation. It has no public HTTP controller. `MANDATORY` means a Spring-managed caller must already have a transaction, keeping choice and token issuance together rather than committing the choice separately.

- `refresh(user, PESSIMISTIC_WRITE)` reloads and locks the user's database row. Reloading matters when another login has already completed a choice; a cached JPA object may be stale. The row lock serializes competing choices for that user, not all users.
- The account-status check rejects restricted accounts, including a restriction committed while credentials were being checked.
- An account with no pending choice may log in normally, but cannot change roles by supplying `selectedRole`.
- A pending account without a choice gets the explicit 409 response; it does not receive tokens.
- Only RENTER or SUBLESSOR is accepted for this migration. This prevents an ADMIN or LANDLORD escalation through the recovery path.
- The assignment-list check rejects inconsistent state instead of guessing which role to replace.
- The selected role is loaded from the database. Failure indicates an incomplete deployment and rolls back the transaction.
- `saveAndFlush` writes the role assignment before completing the user's choice. The database initializes its NOT_STARTED verification record.
- `completeRoleSelection` increments the token version with overflow checking and clears the pending flag.
- `flush` runs the database validation and audit trigger. If it or subsequent token issuance fails, the transaction rolls back all these changes.

Removing the lock allows competing choices to race. Removing the transaction can leave an assignment without its intended account/token transition. Removing the allowlist permits privilege escalation.

### CurrentAccountJwtValidator.validate

Location: `src/main/java/com/makaohub/backend/auth/config/CurrentAccountJwtValidator.java`.

`JwtCryptoConfig` adds this validator alongside the existing signature, issuer, audience and time checks. It is not a replacement for signature verification.

- Require `token_type=access`: another signed token type cannot be used as an access token.
- Parse the JWT subject as a UUID. There is no request-body user ID to override it.
- Require exactly one role claim and parse it through the new enum. Old ROOMMATE_SEEKER tokens are rejected.
- Read an integer authorization version. A missing claim means version zero solely for compatibility with pre-migration tokens of unchanged accounts. Migrated accounts have a higher version, so this does not restore their old access.
- Query `UserRoleRepository.isCurrentAuthorization` once, using bound parameters. The database must simultaneously show an ACTIVE account, no pending choice, the claimed active role and the same authorization version. This avoids combining account state from one query with role state from a later query.
- A failed check returns an invalid-token result. Database errors do not become successful authentication. There is no cache that could continue granting stale access.

RECOMMENDED trade-off: this adds a database read to each bearer-authenticated request in exchange for current authorization and revocation checks. Authorization has a point in time; it cannot retroactively cancel requests already authorized before a state change. High-impact future financial operations also need transaction-local authorization checks.

The version check is a foundation for future profile role switching, not a claim that the role-switch endpoint has been implemented.

## Other changed files and connections

All paths below are relative to `/home/hp/Desktop/makao-hub-backend`. The complete files are in the review copy at `/home/hp/Documents/Codex/2026-09-23/gu/makao-role-review`.

| File/class | What changed, why, and what depends on it |
|---|---|
| `auth/domain/RoleName.java` | Removes ROOMMATE_SEEKER and adds SUBLESSOR. Jackson input and JPA role loading use this enum; the migration must ship with it. |
| `auth/domain/User.java` | Maps the two authentication-state columns and exposes getters. `completeRoleSelection()` accepts only pending users and advances their token version. Without the increment, tokens could survive the transition. |
| `auth/api/LoginRequest.java` | Adds optional `selectedRole`. The two-argument constructor preserves existing Java callers; ordinary login JSON stays valid. Validation annotations remain in force. |
| `auth/api/GoogleAuthRequest.java` | Adds the same optional choice for Google login. This never substitutes for Google token verification. |
| `auth/api/AuthController.java` | Passes the Google choice to its authentication service; the controller still validates the request body. |
| `auth/service/PasswordAuthenticationService.java` | Calls selection resolution after password and account checks and before issuing tokens. The existing dummy hash and password-size checks remain. |
| `auth/service/GoogleAuthenticationService.java` | Calls selection resolution after verified provider identity lookup and account checks. A request cannot nominate another account to modify. |
| `auth/exception/AuthException.java` | Adds explicit role-choice failure reasons. These distinguish UI recovery steps without revealing them before authentication. |
| `shared/error/ApiExceptionHandler.java` | Maps required/already-completed choice to 409 and disallowed choice to 400. Existing error handling is unchanged. |
| `auth/service/AccessTokenService.java` | Requires exactly one selected role and puts `authorization_version` into the signed access token. RS256, issuer/audience/expiry and key ID behavior remain. |
| `auth/service/AuthenticationTokenService.java` | Blocks normal token-pair issuance while a choice is pending, including defensive checks in response creation. |
| `auth/service/RefreshTokenService.java` | Blocks refresh issuance/rotation for pending or restricted users. Its existing family-reuse revocation remains. |
| `auth/repository/UserRoleRepository.java` | Adds the single parameterized JPA authorization query described above. Other ownership/role queries remain available. |
| `auth/config/JwtCryptoConfig.java` | Wires the current-account validator into the real decoder. Without this wiring, a valid signature alone could preserve stale authorization. |
| `auth/config/SecurityConfig.java` | Requires LANDLORD authority for `/api/v1/landlord/**` before controller dispatch. Service-level ownership restrictions remain essential. |
| `verification/domain/RoleVerification.java` | Replaces LandlordVerification as the shared storage entity. Its primary key includes both user and role. Getters and timestamp mappings are routine JPA boilerplate; `markPending()` retains existing submission behavior on the selected role record only. |
| `verification/domain/RoleVerificationId.java` | Record identifying a user/role pair; JPA uses its value equality to distinguish roles for the same account. Omitting the role risks cross-role updates. |
| `verification/repository/RoleVerificationRepository.java` | Replaces the landlord-only repository with role-qualified lookup. Callers must provide both user and role. |
| `verification/service/LandlordVerificationService.java` | Reads/writes LANDLORD specifically in the new storage. Its authorization, document checks, and submission flow remain intact. |
| `profile/api/MyProfileResponse.java` | Adds the active role's `verificationStatus`; no verification documents or encrypted fields are exposed. Record accessors are boilerplate. |
| `profile/service/ProfileService.java` | Reads verification using the authenticated user and database role. Uses NOT_STARTED if evidence is absent. Name editing and private avatar handling retain their existing behavior. |
| `profile/navigation/NavigationItemKey.java` | Adds ROOMMATES for the new Sublessor navigation contract. |
| `profile/navigation/NavigationService.java` | Replaces the obsolete role branch with Sublessor dashboard/listings/roommates/inbox/profile destinations from the screens. Navigation does not grant API permission or implement those future screens. |

The Java paths in that table are under `src/main/java/com/makaohub/backend/`.

## Tests and their purpose

- `PublicAdminRegistrationTest`: both actual registration services reject ADMIN before repository, Google or token activity. Mocks prevent external calls.
- `RegistrationContractTest`: HTTP validation rejects absent, obsolete and multiple roles; accepts the SUBLESSOR contract; rejects RENTER/SUBLESSOR requests to the landlord namespace. Registration services are mocked here, so persistence is checked separately.
- `LegacyRoleSelectionServiceTest`: missing/invalid choices, both valid choices, restricted users and misuse as a general switch. Mock EntityManager verifies the locking call; actual locking is tested against PostgreSQL.
- `GoogleAuthenticationServiceTest`: invalid provider credentials cannot select roles; the stored Google subject determines the account.
- `CurrentAccountJwtValidatorTest`: old versions, old roles, malformed versions and failed database authorization cannot succeed; version-zero compatibility is explicit.
- `AuthorizationQueryMappingTest`: Hibernate compiles the parameterized authorization query against the actual JPA mappings without connecting to a database. This does not replace execution tests.
- `LandlordOwnershipTest`: another landlord's exact location is rejected before location retrieval or decryption. This tests the existing ownership boundary without changing property logic.
- `RoleMigrationIntegrationTest`: migrates a fresh schema through V14, seeds synthetic legacy accounts, applies V15 and validates JPA mappings. Tests account preservation, refresh revocation, both role choices, rollback, concurrent choice, per-role verification isolation, database audit protection, new JPA assignment initialization, and the atomic authorization query. It only accepts a dedicated localhost database URL; every run uses and removes its own random schema.
- Existing `PasswordAuthenticationServiceTest`, `ProfileControllerTest`, `ProfileServiceTest`, and `NavigationServiceTest` were updated for the new constructor/DTO/role contracts. No production credentials are needed for these tests.

## Run and verify

Unit and HTTP tests only, without sourcing backend.env:

```bash
cd /home/hp/Desktop/makao-hub-backend
./mvnw -o '-Dtest=*Test,!RoleMigrationIntegrationTest' test
```

Database tests plus the above tests, using a fresh synthetic PostgreSQL cluster:

```bash
cd /home/hp/Desktop/makao-hub-backend
bash docs/run-role-tests.sh
```

The script defaults to locally installed PostgreSQL 15 binaries. Override `MAKAO_PG_BIN` or `MAKAO_ROLE_TEST_PORT` if necessary. It binds loopback only, creates synthetic data, stops its own server on exit, and leaves its temporary logs/data directory for inspection. Trust authentication is for this temporary synthetic cluster only, never deployment configuration. Maven's `*Test` selection excludes the pre-existing plural `MakaoHubBackendApplicationTests`, which otherwise uses external application configuration.

Successful verification means BUILD SUCCESS, zero failures/errors, and the integration test NOT skipped. The integration class is intentionally skipped when MAKAO_ROLE_TEST_DB_URL is absent. Do not count a skipped database test as a migration pass.

Starting the main application against Supabase would apply V15 automatically if Flyway is enabled. Review the migration, coordinate the client change, stop old instances, and verify backups before doing that.

## Still outstanding in Step 1

- Profile role-switch/add-role endpoint, re-authentication and token-family revocation for general switches.
- Password change/reset and controlled email/phone changes.
- M-Pesa/payout profile fields with the chosen KMS, payout re-verification and confirmed cooling-off period.
- Renter saved preferences and optional lifestyle profile.
- A reusable ownership/role authorization service across later modules; current checks remain enforced locally and by the JWT guard.
- Verification flows for Renter/Sublessor and actual identity/face checks are a subsequent build step. This migration initializes status only.

Remaining risks: production database privilege separation is not provisioned; new database-dependent JWT validation affects availability and latency; actual role switching is not yet implemented. Existing upload/content-scanning and encryption limitations are not resolved by this change.

Provider/legal confirmation: choose the KMS and verification providers before storing new sensitive fields; confirm payout-change verification/cooling-off requirements with the payment provider and sensitive-data/audit retention with Kenyan counsel. No new payment or legal assumptions were encoded here.

Suggested next task: complete the profile role-switch flow against the new role/verification model, then finish the remaining profile fields after their provider/security decisions are confirmed.
