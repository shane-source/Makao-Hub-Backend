-- Notification emails now go out for every new notification. Anything still waiting from before this
-- point is marked skipped so users are not sent a burst of old emails. Listing review emails keep
-- their own activation rule and are left alone.
UPDATE notification_email_outbox o
SET state = 'SKIPPED', updated_at = CURRENT_TIMESTAMP
FROM notifications n
WHERE n.id = o.notification_id AND o.state = 'PENDING' AND n.type <> 'LISTING_REVIEWED';
