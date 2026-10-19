# Release 2 - Notifications: API Contract & Read Model

Status: **APPROVED v1.3.0 (2026-10-18).** Decisions N1-N7 approved as proposed. Migration V11 is done (`docs/milestone-2-erd.md` section 14). Service, endpoints and UI are implemented and tested (unit, web layer, Oracle IT, UI).
Builds on the event pipeline in `docs/release-2-event-streaming.md`, which already fills `NOTIFICATIONS` (V9) from Kafka.

Machine-readable contract: `application/funds-transfer-service/src/main/resources/openapi/notifications/notifications-v1.yaml`, wired into the root spec (v1.3.0). All seven specs pass `scripts/openapi.sh lint`.

## 1. Endpoints

| Method / path | Purpose | Success |
|---------------|---------|---------|
| `GET /notifications?page&size&category&unreadOnly` | One page of the inbox, newest first, plus the total unread count | 200, `no-store` |
| `GET /notifications/unread-count` | Badge count | 200, `no-store` |
| `POST /notifications/{notificationId}/read` | Mark one read | 204 (idempotent) |
| `POST /notifications/read-all` | Mark everything unread now as read | 204 (idempotent) |

```
GET /api/v1/notifications?size=20

200 OK
Cache-Control: no-store
{"items":[{"notificationId":"01928f4e-...","category":"TRANSACTION","type":"TRANSFER_RECEIVED",
  "entityType":"TRANSFER","entityId":"01928f4e-...",
  "params":{"transferId":"01928f4e-...","amount":"25.00","currency":"USD","counterpartyLast4":"1001"},
  "occurredAt":"2026-10-18T09:00:00.000Z","createdAt":"2026-10-18T09:00:00.250Z","readAt":null}],
 "page":0,"size":20,"totalElements":1,"totalPages":1,"unreadCount":1}
```

All endpoints are CUSTOMER only; an OPERATOR gets 403. No new error codes: `INVALID_REQUEST` (bad paging, unknown category, non-UUID ID), `NOT_FOUND`, `FORBIDDEN`, `UNAUTHENTICATED`, `INTERNAL_ERROR`.

## 2. Decisions (approved 2026-10-18)

| # | Decision | Why | Alternative |
|---|----------|-----|-------------|
| N1 | **Notifications belong to the customer**, like beneficiaries (B2): all users of a customer share one inbox and one read state. | `NOTIFICATIONS` is keyed by `CUSTOMER_ID` (V9); the events name customers, not users. | Per-user read state: a `NOTIFICATION_READS (NOTIFICATION_ID, USER_ID)` table. Only matters for joint profiles with several logins. |
| N2 | **The API sends `type` + structured `params`; the client writes the sentence.** Params are allow-listed server side (`transferId`, `amount`, `currency`, `counterpartyLast4`, `beneficiaryId`, `accountIdLast4`); free text and full account numbers are never sent. Unknown types must render generically. | Wording and language change without rewriting stored rows; a newer backend cannot break an older client. The allow-list means a future projector bug cannot leak a field. | Server-rendered `title`/`body`: simpler clients, but every language and wording change becomes a backend release. |
| N3 | **Offset paging, newest first** (`createdAt` desc, `notificationId` tiebreak), `size` 1-50, default 20, same shape as account history plus `unreadCount`. | Consistent with `GET /accounts/{id}/transfers`; inboxes are short and read from the top. | Cursor paging: stable under inserts; worth it only if inboxes grow large. |
| N4 | **Polling, no push.** The badge polls `unread-count` at most once a minute and on window focus. V11 adds an index of unread rows only, so the poll is an index-only scan whatever the history size. | No new infrastructure; a minute is fine for in-app notices. Real-time alerts belong to delivery channels (push/SMS), which are a separate decision. | Server-Sent Events or WebSocket fed by the consumer. |
| N5 | **Marking read is idempotent and keeps the first `readAt`.** `read-all` marks rows stored by the request time, so a notice that lands during the request stays unread. Another customer's notification and an unknown ID are the same 404. | Matches `TRG_NOTIF_IMMUTABLE` (V9: `READ_AT` set once). Retries are safe; nothing is marked read that the user could not have seen. | Mark-unread: needs the trigger relaxed. |
| N6 | **No delete or archive in v1.** Removal is the retention purge (owner identity), still to be agreed with compliance. | The runtime user has no `DELETE` (P8); a notice is a record of what the customer was told. | Soft "dismiss" flag: a new column and migration. |
| N7 | **Reading and marking read are not audited.** | They change no money, recipient or credential, and would multiply `AUDIT_EVENTS` volume by every page view. Security-relevant facts (beneficiary changes) are already audited where they happen. | Audit `read-all` only. |

## 3. Read model

- Source table: `NOTIFICATIONS` (V9), filled only by the Kafka consumer. Mapping from events: `docs/release-2-event-streaming.md` section 4.
- Every read and update is scoped by `CUSTOMER_ID` from the verified token, so another customer's row is never loaded or changed.
- Indexes: `IX_NOTIF_CUSTOMER (CUSTOMER_ID, CREATED_AT)` serves the list (V9); `IX_NOTIF_UNREAD` (V11) serves the unread count, `unreadOnly` and `read-all`.
- Grants: none new. `FUNDS_APP` already has `SELECT, INSERT, UPDATE` on `NOTIFICATIONS` (provisioned with V9).

## 4. UI

- Bell in the top bar with the unread count (capped at `99+`); accessible name "Notifications, N unread". A failed count hides the badge and keeps the link.
- `/notifications`: category filter (All / Transactions / Security), "Unread only", "Mark all as read", one card per notice with "View" (opens the transfer or the beneficiaries page and marks the notice read) and "Mark as read".
- Wording lives in `src/features/notifications/messages.ts`. Security notices end with "If this wasn't you, contact us right away."
- MSW mocks implement the same rules, and emit notices when a mock transfer completes, goes to review or is cancelled, and when a beneficiary is added or deleted, so `npm run dev` shows the full loop.

## 5. Verification (2026-10-18)

| Check | Result |
|-------|--------|
| `scripts/openapi.sh lint` | 7/7 specs valid |
| Unit + web layer (`NotificationServiceTest` 9, `NotificationsWebLayerTest` 9) | Pass; whole suite 173 |
| `NotificationIT` on disposable Oracle Free 23.26, app as `FUNDS_APP` | 5/5: customer scoping and ordering, filters, read once for all users of the customer (trigger accepts it), another customer's row is an unchanged 404, `read-all` leaves later rows and other customers alone, unread count is an index-only scan of `IX_NOTIF_UNREAD` |
| Whole service suite | 173 unit + 37 IT pass (`EventPipelineIT` with a real Kafka broker) |
| UI (`messages.test.ts` 8, `NotificationsPage.test.tsx` 12) | Pass; whole suite 94; `npm run build` OK |

Found while testing: a plain `(CUSTOMER_ID, READ_AT)` index does not serve `READ_AT IS NULL`, because Oracle indexes a `TIMESTAMP WITH TIME ZONE` column as `SYS_EXTRACT_UTC(READ_AT)`; the plan still read the table. V11 therefore indexes `CASE WHEN READ_AT IS NULL THEN CUSTOMER_ID END`, which holds only unread rows, and the repository queries use that exact expression. On 5,000 rows with 10 unread, the index held 10 entries and the count never touched the table.

Not verified: the screen has not been looked at in a real browser (no browser on the build machine). It reuses the existing card, button and alert styles plus a small segmented control and badge.

## 6. Not in this change

- Delivery channels (email, SMS, push) and per-customer channel preferences.
- Per-user read state (N1 alternative) and dismiss/archive (N6).
- Retention purge job for `NOTIFICATIONS`.
- `ACCOUNT` category notices: reserved, no event produces them yet.
