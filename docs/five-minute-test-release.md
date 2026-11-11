# Five-minute TEST booking release

Approved rule: after a successful held booking payment and both booking agreements, the renter confirms move-in, then the landlord or sublessor confirms. The second server timestamp starts a five-minute wait. Staff lead or Payment reviewer may approve the existing TEST release after that deadline. There is no automatic transfer or scheduled release.

For example: renter confirms at 12:00, owner confirms at 12:10, release becomes eligible at 12:15. Repeated confirmation requests do not restart the timer. Rent, deposit and commission amounts do not change.

## Changed code and connections

| File and method | Purpose, connection and reason it is needed |
| --- | --- |
| `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/renter/domain/Booking.java`: `confirmMoveIn` | Records only renter move-in; it no longer creates a release deadline before the owner confirms. Restoring the old assignment would start the clock too soon. |
| Same class: `confirmOwnerMoveIn` | Checks held status, both agreements, renter confirmation and timestamp order; returns immediately on replay; records owner confirmation and its timestamp plus five minutes. The agreement service calls this method under its existing row lock and transaction. Removing a guard permits premature or replay-driven changes. |
| Same class: `markReleased` | Requires both confirmations and a deadline; checks the supplied server release time before assigning `releasedAt`. A rejected early release leaves that field untouched. Removing the time check permits early release. |
| `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/renter/service/BookingAgreementService.java`: `releaseMock`, `requestOwnerReleaseReview` | The existing staff, verification, dispute, payment and idempotency checks remain. Updated messages describe the five-minute rule; review requests still do not authorise payment. Incorrect text would mislead staff about eligibility. |
| `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/renter/api/RenterBookingAgreementController.java`: `confirmMoveIn` | Corrects the endpoint documentation: the renter's action records confirmation, while the owner's subsequent action starts the timer. The API request and response are unchanged. |
| `/home/hp/Desktop/makao-hub-backend/src/main/resources/db/migration/V65__five_minute_test_release_after_both_confirmations.sql` | Replaces the old database timing constraint. Recalculates only unreleased held bookings with both confirmations, increments their versions and appends an audit record for each changed deadline. Completed releases remain untouched. Legacy renter-only deadlines are tolerated until owner confirmation replaces them; they cannot authorise release without the owner. The new constraint requires an explicit non-null deadline once both have confirmed; a second constraint rejects new releases before their deadline. Without this migration, PostgreSQL would reject the new application timestamps. |
| `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/RenterViewModel.kt`: `moveIn` | Updates the success message to explain the second confirmation and five-minute wait. The existing refresh and server submission remain authoritative. |
| `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/LandlordViewModel.kt` and `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/SublessorViewModel.kt`: `confirmMoveIn` | Explain that their recorded confirmation starts the five-minute wait; they still refresh and submit through their role-scoped repositories. Old wording would contradict the server deadline. |
| `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/ui/RenterScreens.kt`: `RenterBookingDetail` | Describes the new wait and shows a deadline only after owner confirmation, so a legacy renter-only deadline is not presented as active. |
| `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/ui/LandlordScreens.kt`: `PaymentHistory`; `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/ui/SublessorScreens.kt`: `SharedPayments` | Show the server deadline as a five-minute wait, or show that both confirmations are still needed. Client clocks never decide release eligibility. |
| `/home/hp/Desktop/makao-hub-web/app/views/admin/payments.scala.html` | Describes the five-minute requirement and avoids displaying a legacy deadline as active when a party has not confirmed. The approval control continues to use the server's `release_ready` flag. |

## Security-critical execution order

1. `confirmMoveIn` requires held payment and both booking agreements before recording the renter timestamp. It creates no deadline.
2. `confirmOwnerMoveIn` requires the same held state, both agreements and renter timestamp. Ownership and verified role are checked by the existing service before this call; this prevents another owner from confirming a booking.
3. An existing owner timestamp returns without mutation, so replay cannot extend or shorten the deadline.
4. A timestamp before the renter's timestamp is rejected. The service supplies server time, preventing a client from backdating the wait.
5. Owner confirmation and `releaseEligibleAt = ownerConfirmation + 5 minutes` persist together under the row lock and optimistic version.
6. On staff approval the service rechecks payment success, owner verification, disputes and eligibility. The domain and database reject release before the deadline; the existing idempotency key prevents duplicate ledger posting.

## Verification and operation

`BookingTest` covers the exact boundary, delayed owner confirmation, unchanged deadlines on replay and no state mutation on early release. `BookingAgreementServiceTest` checks refusal one second before the second-confirmation deadline and successful idempotent release at the deadline. Existing owner-role and ledger tests remain applicable.

`BookingReleaseWindowMigrationTest` runs Flyway on a clean temporary PostgreSQL database and upgrades synthetic V64 rows. It checks landlord/sublessor deadlines, preserved completed releases and renter-only history, audit/version updates, unchanged totals, missing-deadline rejection, early-release rejection and exact-boundary acceptance. Its helper methods create only synthetic users/bookings, read deadlines and issue test release updates; without those fixtures the migration's existing-data behaviour would not be exercised. It refuses non-local database URLs.

`StaffLeadPageAccessTest` verifies the admin page explains the new timing alongside its existing staff permissions, CSRF and submission tests. Removing this assertion could leave stale admin guidance unnoticed. The implementation uses existing Java 21 `Instant`/`ChronoUnit` APIs, Spring Boot 4.1.1 and Play 3.0.12; migration tests use PostgreSQL 15.19.

The timing constraints use `NOT VALID` to leave completed historical rows untouched while checking every new insert or update. Completed historical rows with the old deadline therefore cannot be casually edited under the new rule. Setting `released_at` and a forged deadline together is also rejected; historical compatibility is not a bypass for new releases.

Restart the backend to apply V65 before using the updated Android build. Restart the Play admin website to pick up its text. Do not run the old backend alongside the new database constraint.

Remaining risk: device taps and the actual Paystack checkout require an end-to-end sandbox walkthrough. Live custody and payout arrangements remain TBC with Paystack and Kenyan counsel. RECOMMENDED next task: verify one landlord and one sublessor booking with two devices and Staff lead, including rejection at 4m59s and eligibility at 5m00s after the second confirmation.
