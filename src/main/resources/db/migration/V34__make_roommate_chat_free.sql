/*
 * Roommate chat is free.
 *
 * Existing LOCKED threads were created by the old paid-chat design.
 * They are now opened so participants can communicate without a
 * payment entitlement.
 */
UPDATE roommate_chat_threads
SET
    status = 'OPEN',
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'LOCKED';


/*
 * New database rows must default to OPEN.
 */
ALTER TABLE roommate_chat_threads
    ALTER COLUMN status SET DEFAULT 'OPEN';


/*
 * LOCKED is no longer a valid state for roommate chat.
 */
ALTER TABLE roommate_chat_threads
    DROP CONSTRAINT roommate_chat_threads_status_check;


ALTER TABLE roommate_chat_threads
    ADD CONSTRAINT roommate_chat_threads_status_check
        CHECK (
            status IN (
                       'OPEN',
                       'CLOSED'
                )
            );