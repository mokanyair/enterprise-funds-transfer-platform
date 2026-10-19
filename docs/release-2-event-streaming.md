# Release 2 - Event Streaming (Kafka) for Notifications

Status: **IMPLEMENTED 2026-10-18 at your request ("close the gaps ... for long term").** Decisions E1-E9 were taken as the recommended defaults and are open for review. Migrations V8 and V9; code in `funds-transfer-service`.

Of the remaining Release 2 modules, only **Notifications** consumes Kafka. Statements must come from the ledger, Security Center's data lives in Keycloak, and Payments would produce events, not consume them. This document makes the event stream fit for that consumer and builds the consumer up to the stored notification. The Notifications **API and screen** come next, as their own contract, reading the `NOTIFICATIONS` table this work fills.

## 1. Gaps found and how each was closed

| # | Gap | Effect before | Closed by |
|---|-----|---------------|-----------|
| G1 | `OUTBOX_EVENTS.TRANSFER_ID` was `NOT NULL` with an FK to `TRANSFERS` | Only transfer events could be published. Beneficiary add/delete, the classic "was this you?" alert, could not exist | **V8**: `AGGREGATE_TYPE` + `AGGREGATE_ID`; `TRANSFER_ID` nullable but set exactly for transfer events (`CK_OUTBOX_AGG_TRANSFER`), so the FK still holds for them (E1) |
| G2 | No beneficiary events | Nothing to notify on | `BeneficiaryAdded` / `BeneficiaryDeleted` written in the same transaction as the change; only the winning delete emits one |
| G3 | Events named accounts, not customers | Every consumer would need the transfer service's database to find recipients, including the receiving customer | `parties` array in every event: customer ID + role (`SOURCE`, `DESTINATION`, `OWNER`) (E2) |
| G4 | No envelope or versioning | No way to evolve the payload safely; no aggregate on the wire | Envelope v1: `schemaVersion`, `aggregateType`, `aggregateId`, plus the existing `eventId` / `eventType` / `occurredAt`. Transfer fields stay top-level, unchanged (E2) |
| G5 | One hard-wired topic | Beneficiary events had nowhere to go | One topic per aggregate: `funds.transfer.events` (unchanged) and `funds.beneficiary.events`; key = aggregate ID (E3) |
| G6 | Publisher could only be enabled by editing `application.yml` | Not switchable per environment | `FUNDS_OUTBOX_PUBLISHER_ENABLED` env var; `.env.example` updated (E4) |
| G7 | No topic provisioning | A fresh broker without auto-create rejects every send | Optional `FUNDS_KAFKA_CREATE_TOPICS`: the app creates both topics and their DLTs (dev only; shared clusters provision their own) (E4) |
| G8 | No consumer anywhere | Events went nowhere | `NotificationEventListener`: consumer group `funds-notifications` on both topics, projecting into **V9** `NOTIFICATIONS` (E5) |
| G9 | No consumer-side idempotency | At-least-once delivery would duplicate notifications | `UK_NOTIF_EVENT_CUSTOMER (SOURCE_EVENT_ID, CUSTOMER_ID)`, an existence check in one transaction per event, and offsets committed only after that transaction (`ack-mode: record`) (E6) |
| G10 | No poison-message handling | One bad record would block its partition forever | `DefaultErrorHandler`: retry with back-off, then publish to `<topic>.DLT`; malformed events skip the retries (E5) |
| G11 | Events published before G3 have no `parties` | A consumer would drop them | The projector treats an event without `parties` as v0 and resolves the owners from the account numbers |
| G12 | **Head-of-line block in the publisher**: the pending scan decoded every row, so one undecodable ID failed every poll. An error claiming one row also aborted the rest of the batch | Found in testing: one bad row stopped **all** publishing | Section 8: rows scanned and claimed as raw keys and decoded one at a time; a malformed row is quarantined (`FAILED`); any other per-row error skips only that row for one poll. **V10** stops short IDs being written at all |
| G13 | `FAILED` rows were silent | Exhausted retries went unnoticed | The publisher logs an ERROR with the `FAILED` count at most once a minute (alert on it); section 8 has the requeue procedure |

## 2. Decisions (E1-E9)

| # | Decision | Taken | Alternative |
|---|----------|-------|-------------|
| E1 | Outbox shape | Generalise the one table (V8) | A second outbox table per aggregate: more tables and publishers, same guarantees |
| E2 | Payload | Envelope v1 with `parties`. **Additive**: transfer events keep every v0 field at the top level. Additive changes keep `schemaVersion`; breaking ones bump it. Consumers ignore unknown fields and event types | Nest the transfer under `data`: cleaner, but breaks the v0 shape |
| E3 | Topics and keys | One topic per aggregate type, keyed by aggregate ID (per-aggregate ordering) | One shared topic: simpler, but mixes retention and ACL needs |
| E4 | Enablement and provisioning | Publisher, consumer and topic creation are each env-switched and **off by default**, so nothing changes until an environment opts in | Always on: would fail start-up anywhere without a broker |
| E5 | Where the consumer runs | Inside `funds-transfer-service` for now, as the separate package `notifications/`, behind its own flag. It depends only on the event contract, `NOTIFICATIONS`, and (v0 events only) account lookup, so it can move to its own deployable unchanged | A separate notification service now: cleaner ownership, but a new deployable, schema and identity before there is a read API |
| E6 | Consumer idempotency | Unique (event, customer), checked inside one transaction per event; offset committed after it | A separate inbox table: same effect, one more table |
| E7 | Who is notified of what | Table in section 4. Only the source customer for review, rejection and cancellation. Both parties when a transfer completes, once if they are the same customer. Only the owner, never the saved account's holder, for beneficiary changes | Notify the receiver of a cancelled transfer: they never knew it existed |
| E8 | What events and notifications carry | No free text (transfer reference, nickname) and no full account numbers: only last four characters. Customer IDs are internal UUIDs; no names | Include the nickname: friendlier text, but customer free text in Kafka for the topic's retention |
| E9 | Notification text | Stored as `TYPE` + `PARAMS` JSON; wording rendered at read time | Store rendered text: simpler read, but frozen wording and no localisation |

## 3. Event contract (schema v1)

Code: `events/EventEnvelope.java` (constants), `service/OutboxWriter.java` (producer), `notifications/NotificationProjector.java` (consumer).

```json
{
  "eventId": "0192...", "eventType": "TransferCompleted", "schemaVersion": 1,
  "occurredAt": "2026-10-18T09:00:00Z", "aggregateType": "TRANSFER", "aggregateId": "0b6f...",
  "parties": [{"customerId": "...", "role": "SOURCE"}, {"customerId": "...", "role": "DESTINATION"}],
  "transferId": "0b6f...", "sourceAccountId": "ACC001", "destinationAccountId": "ACC002",
  "amount": "250.00", "currency": "USD", "status": "COMPLETED", "createdAt": "...", "updatedAt": "..."
}
```

```json
{
  "eventId": "...", "eventType": "BeneficiaryAdded", "schemaVersion": 1, "occurredAt": "...",
  "aggregateType": "BENEFICIARY", "aggregateId": "<beneficiaryId>",
  "parties": [{"customerId": "...", "role": "OWNER"}],
  "beneficiaryId": "...", "accountIdLast4": "C002"
}
```

| Topic | Key | Event types |
|-------|-----|-------------|
| `funds.transfer.events` | transfer ID | `TransferCompleted`, `TransferPendingReview`, `TransferRejected`, `TransferCancelled` |
| `funds.beneficiary.events` | beneficiary ID | `BeneficiaryAdded`, `BeneficiaryDeleted` |
| `<topic>.DLT` | as received | Records the consumer gave up on, with Spring's `kafka_dlt-*` exception headers |

Record headers: `eventId`, `eventType`, `aggregateType`, `schemaVersion`. Delivery is at-least-once; deduplicate on `eventId`.

Transfer events still carry full account numbers in their v0 fields, unchanged from V1. Only new fields follow E8.

## 4. Notifications projected (V9 `NOTIFICATIONS`)

| Event | Recipient | `TYPE` | `CATEGORY` | `PARAMS` |
|-------|-----------|--------|------------|----------|
| `TransferCompleted` | source customer | `TRANSFER_SENT` | TRANSACTION | transferId, amount, currency, counterpartyLast4 |
| `TransferCompleted` | destination customer, if different | `TRANSFER_RECEIVED` | TRANSACTION | same; counterparty = source |
| `TransferPendingReview` | source | `TRANSFER_UNDER_REVIEW` | TRANSACTION | as above |
| `TransferRejected` | source | `TRANSFER_REJECTED` | TRANSACTION | as above |
| `TransferCancelled` | source | `TRANSFER_CANCELLED` | TRANSACTION | as above |
| `BeneficiaryAdded` | owner | `BENEFICIARY_ADDED` | SECURITY | beneficiaryId, accountIdLast4 |
| `BeneficiaryDeleted` | owner | `BENEFICIARY_DELETED` | SECURITY | beneficiaryId, accountIdLast4 |

`ACCOUNT` is reserved in `CK_NOTIF_CATEGORY` for future account-state notices.

## 5. Consumer semantics

1. Record arrives (consumer group `funds.notifications.group-id`, `auto-offset-reset: earliest`).
2. Parse and project. Malformed means no `eventId`, a bad timestamp, or no recipient; it is sent straight to the DLT.
3. In one database transaction: for each (event, recipient) not yet stored, insert. A concurrent duplicate hits `UK_NOTIF_EVENT_CUSTOMER`; the retry then finds it stored.
4. Return; Spring commits the offset (`ack-mode: record`). A crash between steps 3 and 4 is a redelivery that stores nothing.
5. Any other failure, such as the database being down, is retried `max-attempts` times `retry-backoff` apart, then dead-lettered so the partition moves on. DLT records need an operator to fix the cause and re-publish them to the main topic, which is safe because of step 3.

## 6. Configuration

| Property | Env var | Default |
|----------|---------|---------|
| `funds.outbox.publisher-enabled` | `FUNDS_OUTBOX_PUBLISHER_ENABLED` | `false` |
| `funds.outbox.topic` / `beneficiary-topic` | | `funds.transfer.events` / `funds.beneficiary.events` |
| `funds.notifications.consumer-enabled` | `FUNDS_NOTIFICATIONS_CONSUMER_ENABLED` | `false` |
| `funds.notifications.group-id` | | `funds-notifications` |
| `funds.notifications.max-attempts` / `retry-backoff` | | `5` / `2s` |
| `funds.kafka.create-topics` | `FUNDS_KAFKA_CREATE_TOPICS` | `false` (dev single broker only) |
| `funds.kafka.partitions` / `replication-factor` | | `3` / `1` |
| `spring.kafka.bootstrap-servers` | `FUNDS_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |

Producer settings are unchanged (`acks=all`, idempotent). Consumer: no auto-commit, `read_committed`.

Rollout order for an environment: apply V8 and V9, add the `NOTIFICATIONS` grant, provision the four topics (or set `create-topics` on a dev box), enable the publisher, then enable the consumer. Enabling the publisher first is safe: events wait on the topics.

## 7. Database changes

- **V8**: `OUTBOX_EVENTS` gains `AGGREGATE_TYPE` (default `TRANSFER`) and `AGGREGATE_ID` (backfilled from `TRANSFER_ID`). `TRANSFER_ID` becomes nullable. New CHECKs `CK_OUTBOX_AGG_TYPE` and `CK_OUTBOX_AGG_TRANSFER`.
- **V9**: `NOTIFICATIONS` (14th table), `UK_NOTIF_EVENT_CUSTOMER`, `IX_NOTIF_CUSTOMER`, and trigger `TRG_NOTIF_IMMUTABLE` (`ORA-20004`): only `READ_AT` may change, once.
- **Grant (provisioning):** `GRANT SELECT, INSERT, UPDATE ON FUNDS_OWNER.NOTIFICATIONS TO FUNDS_APP;` No `DELETE`. Retention purge is an owner job.

Details are in `docs/milestone-2-erd.md` section 13.

## 8. Kafka hardening and operations (G12, G13)

**Publisher, per poll:**
1. Scan up to `batch-size` pending keys as raw bytes, oldest first. Nothing is decoded yet, so a bad row cannot fail the scan.
2. For each key, in its own transaction and its own `try`: claim the row (`SKIP LOCKED`), then decode it.
   - **Undecodable:** quarantined to `FAILED` at once with an ERROR log. It would never publish, so retrying is pointless.
   - **Send failed** (broker down or slow): `ATTEMPTS + 1`. It becomes `FAILED` after `max-attempts` (10), otherwise it is retried next poll.
   - **Claim or bookkeeping threw** (lock timeout, dropped connection): rolled back, logged, and left `PENDING` for the next poll.
   - In every case the loop moves on to the next row.
3. At most once a minute, if any rows are `FAILED`: `ERROR ... N outbox event(s) are FAILED and will not be published until requeued`. **Alert on this message.**

**V10** (`V10__constrain_outbox_id_lengths.sql`) adds `CK_OUTBOX_EVENT_ID_LEN` and `CK_OUTBOX_AGG_ID_LEN`: both IDs must be exactly 16 bytes. Run the pre-check in the script's header first; it must return 0.

**Runbook: requeue FAILED outbox events** (after fixing the cause, for example the broker is back or the topic now exists). The runtime identity can do this; it has UPDATE on the table.
```sql
SELECT EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, ATTEMPTS, LAST_ATTEMPT_AT FROM OUTBOX_EVENTS WHERE STATUS = 'FAILED';
UPDATE OUTBOX_EVENTS SET STATUS = 'PENDING', ATTEMPTS = 0 WHERE STATUS = 'FAILED' AND EVENT_ID = HEXTORAW(:id);
COMMIT;
```
Requeueing is always safe: consumers deduplicate on `eventId`.

**Runbook: replay the consumer's dead-letter topic** (after fixing the cause in the consumer or the database). Inspect first. The `kafka_dlt-exception-*` headers say why each record failed:
```bash
kafka-console-consumer.sh --bootstrap-server $BROKER --topic funds.transfer.events.DLT --from-beginning \
  --property print.key=true --property print.headers=true --timeout-ms 10000
```
Then copy the records back to the main topic, keeping the key so ordering per transfer is kept:
```bash
kafka-console-consumer.sh --bootstrap-server $BROKER --topic funds.transfer.events.DLT --from-beginning \
  --property print.key=true --property key.separator='|' --timeout-ms 10000 \
| kafka-console-producer.sh --bootstrap-server $BROKER --topic funds.transfer.events \
  --property parse.key=true --property key.separator='|'
```
Replaying is always safe: `UK_NOTIF_EVENT_CUSTOMER` absorbs anything already projected. Records the projector rejects as malformed will dead-letter again; those need a code fix, not a replay. Do the same for `funds.beneficiary.events.DLT`.

**Monitor:** consumer-group lag for `funds-notifications` on both topics; the size of the `.DLT` topics (alert when it is above 0); the `FAILED` outbox log line above; and `PENDING` rows older than a few minutes, which mean the publisher is off or stuck:
```sql
SELECT COUNT(*) FROM OUTBOX_EVENTS WHERE STATUS = 'PENDING' AND CREATED_AT < SYSTIMESTAMP - INTERVAL '5' MINUTE;
```

## 9. Verification (2026-10-18)

Run against a disposable Oracle Free 23.26 and a single-node Apache Kafka 3.9.1 broker with topic auto-creation off, so the app had to create its own topics and DLTs. Both were removed afterwards.

- Unit: `OutboxWriterTest` (envelope, no free text), `KafkaEventSinkTest` (topic routing, key, headers), `NotificationProjectorTest` (every mapping row, own-account transfer, v0 events, malformed input, no free text), `NotificationEventHandlerTest` (dedup, partial redelivery, v0 routing), and `BeneficiaryServiceTest` now asserts the events. 150 unit tests pass.
- `EventPipelineIT`, end to end: published and projected to both parties; beneficiary security notices; a redelivered record creates no duplicate; a poison record is dead-lettered and the partition keeps flowing. 4/4 pass, together with `PostingIT`, `BeneficiaryIT` and `PrivilegeIT` (32 IT in total). `PrivilegeIT` now also proves no `DELETE` on `NOTIFICATIONS`.
- Migrations: V8 applied over V7 data backfills correctly; 18 constraint and trigger checks pass (`docs/milestone-2-erd.md` section 13).
- **Hardening re-run (G12, G13, V10):** `OutboxPublisherTest` (5 tests) covers four cases: a malformed row is quarantined while the rows behind it publish; a row that can't be claimed is skipped for one poll only; a broker failure counts an attempt and the batch continues; and the FAILED report is rate-limited and can never break publishing. V10 was applied over V9, `validate` passed, and 4 checks pass (16-byte IDs accepted; 1-byte and 15-byte IDs rejected on insert and update). The full clean build passes: 155 unit + 32 IT. All 5 events written while the publisher ran were `PUBLISHED`; the other suites' 67 rows correctly stayed `PENDING`, because the publisher context closes after `EventPipelineIT`. There were 0 duplicate notifications.
- Two test problems found and fixed while doing this: a cached test context kept publishing during later test classes (now `@DirtiesContext`), and a synthetic outbox row with a 1-byte ID blocked the publisher. The second is noted as an operational caveat in the ERD.

Run it yourself: start Oracle and Kafka, then `FUNDS_IT_ENABLED=true FUNDS_IT_KAFKA_BOOTSTRAP_SERVERS=<host:port> mvn verify` (plus the `FUNDS_DB_*` / `FUNDS_IT_OWNER_*` variables from the service README).

## 10. Not in this change

- Notifications **API and screen**: done separately, see `docs/release-2-notifications-contract.md` (V11).
- Delivery channels (email, SMS, push).
- Keycloak security events (logins, MFA changes) for Security Center: a separate stream from Keycloak itself.
- A schema registry: the JSON envelope plus `schemaVersion` is the contract for now.
- Retention periods for `NOTIFICATIONS` and topic retention: to be decided with compliance.
