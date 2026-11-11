# Booking payment status synchronization

The reported TEST transaction was independently verified through Paystack's read-only transaction API: the provider returned success and the currency/amount matched the stored booking. Makao still recorded its payment and booking as pending. This identifies a missing synchronization step, not a reason to collect another payment. No credentials, references or personal data are included here.

The booking screen previously read only the stored booking. Paystack verification ran from the payment screen or a delivered webhook. Opening a pending booking now looks up the authenticated renter's existing payment, calls the existing server verification endpoint, then reloads the booking. This also covers reopening a booking after the app process or checkout-return state has been lost.

## Changed files and methods

| File and method | What it does, why it is needed, and what removing it breaks |
| --- | --- |
| `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/payment/repository/PaymentIntentRepository.java`: `findFirstByPayerUserIdAndBookingIdAndPaymentTypeOrderByCreatedAtDesc` | Returns the latest payment for the supplied booking, payment type and authenticated payer together. The query never trusts a client-supplied payer ID. Without this lookup, a fresh app process cannot find the existing reference from a booking safely. |
| `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/payment/api/PaymentQueryController.java`: `bookingPayment` | Adds read-only `GET /api/v1/payments/bookings/{bookingId}`. It validates authentication and active RENTER role, then uses the payer-scoped query restricted to BOOKING payments. Missing or foreign payments return 404, other roles return 403. Without these filters another account could discover a payment reference. |
| `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/data/MakaoApi.kt`: `renterBookingPayment` | Declares the authenticated GET contract above. It accepts only the booking ID and authorization header; it accepts no amount, recipient or status. |
| `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/data/RenterRepository.kt`: `booking` | Optionally checks a pending booking payment using the existing reference and existing Paystack reconciliation POST, then fetches authoritative booking state again. It never creates a checkout. Without the optional check, refreshing the booking repeats the stale local status. Held bookings need only the normal booking read. |
| `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/RenterViewModel.kt`: `go`, `openBooking`, `refreshBooking`, `moveIn`, `openBookingFromPayment`, `foreground` | Enable the optional check for Paystack booking navigation, refresh, resume and confirmation attempts. Mock mode keeps its existing path. `refreshBooking` reports either confirmed/held or still pending; `moveIn` still requires the verified held state and both agreements. Removing these calls leaves one of the same navigation paths stuck. |
| `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/ui/RenterScreens.kt`: `RenterBookingDetail` | Shows “Check existing Paystack payment” while pending. Existing progress/error feedback remains visible; this action invokes refresh and cannot submit a second charge. |
| `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/payment/api/PaymentQueryControllerTest.java` | Tests payer/type query scope, foreign-payment 404 and wrong-role 403. These tests protect the new payment-reference lookup from authorization regressions. |
| `/home/hp/Desktop/makao-hub-frontend/app/src/test/java/com/makaohub/android/RenterContractTest.kt` | Reproduces a pending booking whose existing Paystack transaction succeeds, verifies the request sequence and updated held state, and proves pending results remain blocked. The tests assert no checkout is created. |

## Security-critical sequence

1. The Android auth repository authorizes the RENTER workspace and reads a renter-owned booking through the existing backend ownership check.
2. Only PAYMENT_PENDING bookings trigger the extra lookup; already held bookings do not repeatedly contact Paystack.
3. The lookup endpoint derives the payer ID from JWT subject and requires exactly the RENTER active role. Its repository query includes that payer, booking ID and BOOKING type, preventing cross-account reference access.
4. The existing reconciliation controller verifies reference ownership again before calling Paystack. Paystack's secret remains on the server.
5. The existing service checks Paystack reference, integer minor amount and KES currency, locks the payment and booking, records successful provider/booking transitions, and posts the existing balanced hold ledger. A browser return or client claim is never sufficient.
6. A non-successful verification, provider error or authorization error cannot produce a held state in the app. It never starts a replacement payment automatically. Existing replay controls and ledger uniqueness apply.
7. The booking is reloaded before move-in prerequisites are evaluated. Agreement checks, both move-in confirmations, five-minute wait and staff approval are unchanged.

The provider behavior follows [Paystack's transaction verification documentation](https://paystack.com/docs/payments/verify-payments/). This change uses existing Spring Boot 4.1.1 repository infrastructure and Android Retrofit/coroutine patterns; no new dependencies or data migration were added.

## Verification and next step

The targeted backend suite covers the new lookup and existing Paystack verification, booking settlement, role wiring and release service. Android tests cover the missing-webhook/reopened-booking path and genuine pending results. Restart the backend and install the updated app, then open the affected booking or tap its existing-payment check.

Remaining risk: the local webhook may still be unreachable from Paystack; this explicit verification path does not make a localhost webhook public. RECOMMENDED next task: test the updated booking check on a device and configure a reachable sandbox webhook for automatic notification. Real custody and payout arrangements remain TBC with Paystack and Kenyan counsel.
