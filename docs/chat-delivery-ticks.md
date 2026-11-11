# Chat delivery ticks

## Requirement, scope and design

Replace the per-message Saved/Received words with ticks in all three Android role
workspaces. One tick means the backend persisted the outgoing message. Two ticks
mean the recipient app acknowledged obtaining the message. Neither indicates read
status. Incoming bubbles show their timestamp without a redundant Received label.
Masked-contact warnings remain independent of receipt state.

Previously neither chat API recorded recipient receipt; broker submission and an
online connection were not evidence of delivery. The minimal implementation adds
one nullable `delivered_at` column to each existing message table and uses the
current authenticated STOMP connection, private queues and history API. No read
tracking, presence inference, payment changes, dependency, public queue or push
notification service is added. Java 21 / Spring Boot 4.1.1 and the existing Android
Compose, Gson and OkHttp dependencies remain unchanged.

Old messages stay unknown until actually fetched and acknowledged by the recipient;
there is no assumed historical delivery or user-data backfill.

## API and security-critical execution order

Send a STOMP JSON body `{threadId, messageIds}` to `/app/rental-chat.delivered`
(renter/landlord) or `/app/chat.delivered` (renter/sublessor). Batches contain 1–100
UUIDs. There is no accepted recipient ID, sender ID, delivery timestamp or receipt
boolean: the actor comes from the authenticated principal, and time from the server.
Updated message responses/history have an optional `deliveredAt` timestamp.

1. The existing JWT interceptor revalidates the session. The destination interceptor
   permits only the two explicit new application routes, never direct client
   publication into broker queues. This prevents spoofed delivery broadcasts.
2. Controller `acknowledge` derives the user ID from that authenticated principal.
   It cannot accept an arbitrary recipient identity from the JSON body.
3. Each service validates the batch and sorts/deduplicates IDs. It enforces a
   separate, bounded per-account receipt budget using the existing configured rate.
   The send budget is not consumed by receiving messages. Batch and cache bounds
   limit amplification and memory use.
4. Reuse the chat's active-account, stored-role and participant checks. Rental
   acknowledgement additionally retains rental chat's existing role-verification
   checks; roommate acknowledgement retains roommate chat's open-thread rule.
   Nonparticipants and changed-role accounts cannot acknowledge any thread.
5. Repository `lockIncoming` locks only the requested ID in that authorized thread
   whose sender differs from the authenticated actor. This prevents sender-forged
   two ticks and cross-thread ID substitution. No unmasked content is returned.
6. Sorted row locking serializes competing batches consistently. `confirmDelivery`
   sets time only when absent. Retries and concurrent ACKs preserve the first
   timestamp. A missing, own or foreign ID throws and rolls back the entire batch.
7. The service returns only existing masked message responses. Spring commits the
   transaction before the controller calls the existing publisher. Rental uses the
   existing retry-shaped result to notify only the original sender; roommate uses
   its existing private participant publication. Every recipient session is still
   reauthorized by the publisher. No broker-submission count is treated as delivery.
8. Android merges receipt updates by message ID while preserving an already-present
   timestamp against older echoes/history. Thus out-of-order network responses
   cannot turn two ticks back into one or duplicate a bubble.

## Changed backend files, classes and methods

- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/api/AcknowledgeChatMessagesRequest.java`:
  shared request record; `validatedIds` bounds input and produces unique sorted
  UUIDs. Both services call it even outside HTTP/STOMP validation. Removing it loses
  the bounded protocol contract, consistent lock order and malformed-input guard.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/config/StompDestinationAuthorizationInterceptor.java`:
  its allowlist permits only the two receipt handlers; `preSend` keeps the existing
  authenticated-only rule. Without the entries, legitimate acknowledgements fail;
  a wildcard would permit unauthorized routes instead.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/api/RentalChatWebSocketController.java`
  and `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/api/RoommateChatWebSocketController.java`:
  new `acknowledge` methods execute the principal-derived service calls and publish
  only after commit. Removing them makes the client protocol unhandled; publishing
  inside the service transaction could announce a delivery that later rolls back.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/service/RentalChatMessageService.java`
  and `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/service/RoommateChatMessageService.java`:
  `acknowledge` implements the ordered checks above, under one transaction per
  batch. Existing `toResponse` methods add the persisted receipt to both live and
  history messages. Removing the new methods breaks persistence/authorization;
  omitting the mapping loses double ticks when reopening a conversation.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/repository/RentalChatMessageRepository.java`
  and `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/repository/RoommateChatMessageRepository.java`:
  `lockIncoming` uses parameterized JPQL plus a pessimistic write lock, constrained
  by message ID, thread ID and non-sender actor. Removing any predicate weakens
  ownership checking; removing the lock permits duplicate ACKs to overwrite the
  first timestamp. At most 100 ordered rows are locked per batch.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/domain/RentalChatMessage.java`
  and `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/domain/RoommateChatMessage.java`:
  map the nullable receipt; `getDeliveredAt` supplies responses; `confirmDelivery`
  preserves the first nonnull server time. Removing this state means persistence
  cannot distinguish a saved message from recipient-acknowledged delivery.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/api/RentalChatMessageResponse.java`
  and `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/api/RoommateChatMessageResponse.java`:
  response records add nullable `deliveredAt`, leaving existing fields intact.
  Without it clients would need to invent status instead of using server evidence.
- `/home/hp/Desktop/makao-hub-backend/src/main/java/com/makaohub/backend/messaging/service/ChatMessageRateLimiter.java`:
  new `requireDeliveryPermission` uses a separate bounded/expiring bucket cache
  with the same configured per-minute allowance. Removing it leaves receipt spam
  unbounded; sharing the original bucket would reduce legitimate send allowance.
- `/home/hp/Desktop/makao-hub-backend/src/main/resources/db/migration/V67__add_chat_delivery_receipts.sql`:
  adds only nullable timestamps, leaving existing records unchanged. Removing it
  makes the new mapping incompatible with the database. Applied migrations are
  not edited; no payment, entitlement or account data changes.

## Changed Android files, classes and methods

- `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/data/LandlordRepository.kt`:
  shared `OwnerMessage` adds optional `deliveredAt`, defaulting to null for old
  responses. `mergeChatMessages` merges by ID with monotonic receipt state and
  stable chronological ordering. All three ViewModels use it. Removing either
  breaks compatibility, creates duplicate bubbles, or regresses ticks on late data.
- `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/data/RentalChatSocket.kt`:
  `acknowledgeReceived` sends bounded batches only on a ready connection to its
  channel's handler. It deduplicates accepted frame sends with a bounded local set;
  `openSocket` clears that set so reconnects can safely retry. No optimistic receipt
  state is set by enqueueing a frame. Removing these guards permits ACK loops,
  unbounded memory or false double ticks.
- `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/RenterViewModel.kt`:
  `connect` now merges receipt updates and refreshes latest history after connection
  to recover missed updates; generation checks reject stale results. `olderMessages`
  merges against current state after loading, preserving concurrent socket updates.
  `acknowledgeReceived` forwards to the selected socket/thread. Removing these
  paths leaves renter delivery state stale, duplicated or unable to acknowledge.
- `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/LandlordViewModel.kt`:
  `connectChat`, `olderMessages` and `acknowledgeReceived` perform the equivalent
  owner-role work with existing account/chat generation guards. Removing them
  leaves landlords with stale ticks or no recipient acknowledgements.
- `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/SublessorViewModel.kt`:
  `connect`, `olderMessages` and `acknowledgeReceived` do the same for roommate
  chats. The old append-then-distinct merge kept stale messages and is replaced so
  receipt updates actually change existing bubbles. Removing this prevents updates.
  All three reconnect refreshes reset the pagination cursor to the newest returned
  page while retaining cached messages, so missed-history gaps can be paged through.
  Refresh failure keeps the connection and asks the user to reopen; no receipt is
  fabricated. Cancellation and stale-session checks remain intact.
- `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/ui/ProtectedChat.kt`:
  `ProtectedConversation` acknowledges only received, not-owned messages once a
  connection is ready and the thread is open. It renders timestamp/masking notice
  separately from outgoing ticks; accessibility announces Sent or Delivered instead
  of reading glyphs. `protectedChatTicks` maps own saved messages to one tick and
  server-acknowledged ones to two, never inferring read status. The send button
  now says Sending while waiting for the server. Removing these changes leaves
  the old words or lets sender echoes trigger false delivery acknowledgements.
- `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/ui/RenterScreens.kt`,
  `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/ui/LandlordScreens.kt`,
  `/home/hp/Desktop/makao-hub-frontend/app/src/main/java/com/makaohub/android/ui/SublessorScreens.kt`:
  their existing shared-conversation call sites connect the new `onReceived`
  callback to the respective ViewModel. Without that wiring the indicator could
  never progress beyond one tick for the opposite participant.

## Tests

- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/messaging/ChatDeliveryTest.java`:
  checks unknown→delivered state, replay-preserved timestamps, malformed/bounded/
  sorted batches, separate send/receive limits, authenticated handler access and
  rejection of direct client writes into broker queues. Removing these loses the
  smallest unit/protocol checks for the new state machine and trust boundaries.
- `/home/hp/Desktop/makao-hub-backend/src/test/java/com/makaohub/backend/messaging/ChatDeliveryIntegrationTest.java`:
  `setup` migrates an isolated local-only schema with synthetic participants,
  listings, applications/interests and both chat types; `user`/`message` seed those
  records, `ack` wraps the real service in a transaction and `delivered` reloads
  state. Tests cover both directions, history persistence, repeat receipt IDs,
  sender/outsider/cross-thread rejection, all-or-nothing rollback, inactive/changed
  roles, rental verification and concurrent receipt locking. `cleanup` removes
  only its generated test schema. Removing it leaves real database behavior unproven.
- `/home/hp/Desktop/makao-hub-frontend/app/src/test/java/com/makaohub/android/ui/ProtectedChatTest.kt`:
  tests single/double tick meaning, no incoming ticks, unchanged masking, old/new
  JSON, duplicate-free monotonic merging and existing timestamp formatting.
- `/home/hp/Desktop/makao-hub-frontend/app/src/test/java/com/makaohub/android/RentalChatSocketTest.kt`:
  adds a mock-socket test for both receipt routes, 100-ID chunks, duplicate-send
  suppression and no client-supplied recipient/time. Existing masking, framing
  and reconnect tests remain. Without these Android could silently break the
  receipt contract while the backend tests still pass.
- This document records the protocol and rationale; removing it loses the intended
  distinction between persisted, delivered and read states.

## Verify and remaining risks

Validation on 3 November 2026: Android compiled and all 104 unit tests passed.
The backend compiled; 343 tests passed, zero failed/errored, and one unrelated OCR
test was skipped because `TESSERACT_TEST_PATH` is absent. All 67 migrations were
exercised on fresh local schemas, including four real-database delivery tests.
The environment-dependent application-startup class (`*Tests`) is outside the
script's `*Test` selection. No real-device/broker acceptance test was performed.

Run `bash docs/run-role-tests.sh` from `/home/hp/Desktop/makao-hub-backend`, and the
existing Android compile/unit-test tasks from `/home/hp/Desktop/makao-hub-frontend`.
Tests use synthetic accounts and a temporary local PostgreSQL cluster, not Supabase.

Restart the backend to apply V67; rebuild/install Android on both test devices.
With only one person connected, send a message: the sender should see one tick.
When the other person opens that chat, it should become two ticks. Test both
directions for renter/landlord and renter/sublessor, reopen/reconnect, load older
messages, and attempt contact sharing (masking notices must still appear).

An older app does not send ACKs, so its recipient deliveries remain single tick.
If no recipient conversation is open, delivery is not inferred from global online
presence. There is no claim of reading; clients can only acknowledge their own
receipt. Failed/lost ACKs are retried on reconnect or reopening; persisted history
is authoritative. A reconnect refresh loads the newest page, with older pages
available through existing pagination. A two-device visual/network acceptance
check remains necessary; unit tests do not prove real device or broker availability.
RECOMMENDED next task: complete that two-device acceptance check on the rebuilt app.
Before production, confirm receipt-timestamp disclosure/retention with privacy
counsel and hosting/WebSocket limits with the provider. No new Safaricom/Paystack
policy or custody decision is introduced by this messaging-only change.
