# Notification email delivery

New in-app notifications enqueue a generic email in the same database transaction. The email does not include notification text, identity documents, payment data or links. Existing notifications are not backfilled. This covers notifications inserted into the `notifications` table; it does not create new chat-message notification events.

The worker is disabled unless `MAKAO_NOTIFICATIONS_EMAIL_ENABLED=true`. It uses the existing verified-TLS SMTP configuration and sender address. Install migration V77 before enabling the worker. Enqueuing starts when the migration is installed, so notifications created while the worker is disabled will be sent when it is enabled.

The PostgreSQL trigger also covers notifications created directly by admin and booking workflows. The outbox has row-level security enabled; before rollout, verify both the application database role and migration role can insert notification rows and process the outbox under the intended least-privilege grants. No Supabase client access policy is provided.

The worker claims one queued item atomically, sends outside the claiming transaction, and records `SENT`, `FAILED` or `SKIPPED`. A process crash can leave `SENDING`. SMTP failures or crashes may have been accepted by the provider, so the worker does not automatically retry ambiguous outcomes. Operators must investigate `FAILED` and `SENDING` states; no automated alert or operator retry interface is included yet. `SENT` means the mail server accepted the send, not that the recipient received it.

Before production use, back up the existing database, test all pending migrations on a copy, verify role grants and SMTP delivery with an authorized test account, and review notification-channel and retention requirements. Do not enable Flyway on the existing Render database without reviewing all pending migrations.
