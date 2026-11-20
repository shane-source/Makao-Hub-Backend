# Amenities and listing reviews

Migration V79 creates the staff-managed amenities catalogue and separate landlord/sublessor listing selections. Existing supported amenities are carried over. Only a staff lead can add names; listing owners can change choices only in an unsubmitted draft. New catalogue selections are visible to renters on approved, published listings. Existing supported owner flags remain synchronized when the updated Android editor saves.

The review action approves and publishes an eligible listing in one transaction after current verification, location, confirmed image, and plan-limit checks. Rejection returns it to draft. The existing permissioned verification review requires reviewers to open and mark current required evidence reviewed before approving; rejection records a reason.

V79 records an activation time for listing-review email delivery. The dedicated worker sends only listing-review notifications created after that time, avoiding release of the pre-existing notification backlog. It uses the existing SMTP and OTP sender settings. Set `MAKAO_NOTIFICATIONS_LISTING_EMAIL_ENABLED=false` to disable this worker. SMTP acceptance or failure is recorded in the existing outbox; failures and ambiguous sends are not automatically retried.

Run pending Flyway migrations against the intended database before using the new application versions. Verify delivery using a test account with working SMTP; compilation does not verify database grants, migration state, the mail provider or a real phone.
