# Milestone 1 - API Contract & Domain Design

Status: **APPROVED v1.0 (2026-09-18), amended A1 (v1.0.1); amendment A2 (v1.0.2, idempotency) awaiting approval.** Decisions D1-D7 accepted as proposed. Contract only: nothing below is implemented yet.
Machine-readable contract, modular by domain, under `application/funds-transfer-service/src/main/resources/openapi/`:

| File | Domain | Contents |
|------|--------|----------|
| `funds-transfer-v1.yaml` | root | Wires the domains together; same path as before. `scripts/openapi.sh bundle` flattens it to one file |
| `transfers/transfers-v1.yaml` | transfers | `POST /transfers`, `GET /transfers/{id}`, `POST /transfers/{id}/cancel`; state machine, idempotency, 409 and 422 responses |
| `accounts/accounts-v1.yaml` | accounts | `GET /accounts/{id}/balance`, `GET /accounts/{id}/transfers`; `Balance` |
| `identity/identity-v1.yaml` | identity | Bearer scheme, `Role`, 401 and 403 responses. **No endpoints in V1** (users are provisioned outside V1, D2) |
| `common/common-v1.yaml` | shared | `Money`, `AccountId`, `Error` and the closed `ErrorCode` set, correlation ID, 400/404/500 responses |

Dependencies run one way: `common` <- `identity` <- `transfers` <- `accounts`. Every file is a valid OpenAPI document on its own. The split changes no path, status code, error code or field; the bundled output was checked to be equivalent to the previous single file (24 comparisons), except that operations now carry a `tags` value (`transfers` or `accounts`), which lets generators produce one API interface per domain.

## 1. Decisions (all approved as proposed, 2026-09-18)

| # | Decision | Approved position | Why it matters |
|---|----------|----------|----------------|
| D1 | Sync vs async posting | **Synchronous**: validate, post and commit in one transaction; `201` carries `COMPLETED` or `PENDING_REVIEW`. | The briefing requires balances, ledger, state and outbox to change atomically. `RECEIVED`/`VALIDATING`/`PROCESSING` stay in the state machine but are only visible after a crash. If you want async (202 + worker), the create response, cancel rules and worker design all change. |
| D2 | Roles | `CUSTOMER` (owner) and `OPERATOR` (support). Signatory / multi-user account access is **out of V1**. | Drives the authorization matrix and `APP_USERS`. |
| D3 | Timeouts | Server deadline 10 s, DB lock wait 5 s, client timeout >= 15 s. | Values are placeholders; confirm with load expectations. |
| D4 | Currencies | `USD` only. | Affects the CHECK constraint and minor-unit scale (USD = 2). |
| D5 | `PENDING_REVIEW` visibility | Shown to the client as-is. | It hints that a risk rule fired. Alternative: expose it as `PROCESSING`. |
| D6 | Who can cancel | Source-account owner or operator. Destination-only owner gets `403`. | See matrix. |
| D7 | Negative amounts | Malformed (`400`), not `422 AMOUNT_NOT_POSITIVE`. Only zero returns the 422. | Keeps `Money` a single non-negative type shared with balances. |

## 2. Authorization matrix

Identity and role come from the verified token, mapped through `APP_USERS.IDP_SUBJECT`.
"Not visible" always returns an identical `404`.

| Operation | CUSTOMER owns source | CUSTOMER owns destination only | CUSTOMER, unrelated | OPERATOR | Unauthenticated |
|-----------|----------------------|--------------------------------|---------------------|----------|-----------------|
| `POST /transfers` | Allowed | n/a (source not owned -> 404) | 404 | 403 | 401 |
| `GET /transfers/{id}` | Allowed | Allowed | 404 | Allowed | 401 |
| `POST /transfers/{id}/cancel` | Allowed (if cancellable) | 403 | 404 | Allowed (if cancellable) | 401 |
| `GET /accounts/{id}/balance` | Allowed (own account) | n/a | 404 | Allowed | 401 |
| `GET /accounts/{id}/transfers` | Allowed (own account) | n/a | 404 | Allowed | 401 |

Rules that follow from the matrix:
- Never accept a user ID in a request body, path or query.
- Ownership is checked in the query (`WHERE account_id = ? AND customer_id = <caller's>`), so an unauthorized row is never loaded.
- A non-existent and an unauthorized account must be indistinguishable in status, body shape and, as far as practical, latency.
- A non-existent destination account returns the same `422 ACCOUNT_INACTIVE` as an inactive one.

## 3. State-transition table

| From | Allowed next states | Notes |
|------|---------------------|-------|
| `RECEIVED` | `VALIDATING`, `CANCELLED` | |
| `VALIDATING` | `PENDING_REVIEW`, `PROCESSING`, `REJECTED`, `CANCELLED` | |
| `PENDING_REVIEW` | `PROCESSING`, `REJECTED`, `CANCELLED` | Manual review outcome. |
| `PROCESSING` | `COMPLETED`, `FAILED` | Not cancellable: posting may be under way. |
| `COMPLETED` | none | Terminal. Undo = new linked reversal transfer (`reversal_of`). |
| `REJECTED` | none | Terminal. |
| `FAILED` | none | Terminal. |
| `CANCELLED` | none | Terminal. `cancel` on it returns `200` (idempotent). |

Invariants:
- Any transition not in the table is refused in code and covered by a test for every (from, to) pair.
- Kafka publication is tracked on `OUTBOX_EVENTS.status`, never on the transfer.
- A committed posting is never cancelled in place.
- Posting transaction, in order: lock both accounts in ascending account-ID order -> check available funds under lock -> update both balances -> insert two balanced ledger entries -> set transfer `COMPLETED` -> insert outbox event -> commit. Any failure rolls back everything.

## 4. Idempotency behaviour (amended A2, v1.0.2)

Policy:
- The key is scoped to `(authenticated user, Idempotency-Key)`; only `POST /transfers` takes one.
- Same key and payload replays the original result. A different payload returns `409`. A concurrent request returns `409`.
- **Retention is configurable** and bounds only how long the *response* is replayable. The key stays recorded.
- **Expired keys must not permit duplicate posting**: a retry after the replay window returns `409 IDEMPOTENCY_KEY_EXPIRED` and is never processed.
- **Recovery reconciles incomplete requests** (section 4b).

| Situation | Response |
|-----------|----------|
| First request | Process; store outcome. |
| Same actor, key and body; completed; inside replay window | Replay stored outcome, same status code and body, `Idempotent-Replayed: true`. Error bodies carry the current request's `correlationId` and `timestamp` |
| Same actor, key and body; completed; **replay window elapsed** | **`409 IDEMPOTENCY_KEY_EXPIRED`**, not processed again. `Location` of the transfer when one was created |
| Same actor and key, different body | `409 IDEMPOTENCY_KEY_REUSED` (also after the window; the record is still there) |
| Same actor and key, first still running | `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After: 1` |
| Same actor, key and body; earlier attempt abandoned (crash, timeout, error) | Processed once, as a fresh attempt (section 4b) |
| Different actor, same key | Independent; no interaction |
| Missing or non-UUID key | `400 INVALID_REQUEST` |

Payload equality: SHA-256 of the canonical body. Object keys sorted; `amount` compared by decimal value (`250.00` = `250.0`); absent `reference` = `null`; other strings exact, whitespace included.

### 4a. Why an expired key cannot double-post

The stored *response body* is purged after retention; the *record* is not. `UNIQUE(USER_ID, IDEMPOTENCY_KEY)` therefore keeps rejecting a second insert, and the retry is answered `IDEMPOTENCY_KEY_EXPIRED` from the surviving record. The runtime user has no `DELETE` on the table. A completed record is also frozen by a database trigger: nothing but the stored body can change.

How long the record lives:
| Record | Kept until |
|--------|-----------|
| Completed, with a transfer | As long as the transfer (financial-record retention, commonly 5-7 years) |
| Completed, no transfer (a stored `422`) | `tombstone-retention` (default 90 days). Safe to drop: nothing was posted, so reusing the key cannot duplicate a posting |
| `ABORTED` | Same as above; nothing was posted |

Residual limit, stated plainly: when a transfer is finally purged at end of retention, its key record goes with it, and a key that old could no longer be recognised. Option I5 below closes that.

### 4b. Recovery of incomplete requests

Request flow: (1) short transaction inserts the record `IN_PROGRESS` with a lease and commits, so concurrent retries see it and get `409`; (2) the posting transaction runs; (3) **in that same transaction** the record becomes `COMPLETED`. Posting and completion commit or roll back together, so a crash can leave a record `IN_PROGRESS` but never a posted transfer with an unfinished record.

| Record state | Meaning | Next |
|--------------|---------|------|
| `IN_PROGRESS`, lease live | An attempt is running | Others get `409 IN_PROGRESS` |
| `IN_PROGRESS`, lease expired | Attempt died or is very slow; nothing committed | A retry may take over |
| `ABORTED` | Attempt failed cleanly, or the reconciler closed a dead one; nothing posted | A retry may take over |
| `COMPLETED` | Terminal | Replay, or `KEY_EXPIRED` after the window |

**Takeover is a compare-and-set** on an `ATTEMPT` counter (a fencing token): exactly one retry bumps `ATTEMPT` and continues. The posting transaction locks the idempotency row first and verifies `ATTEMPT` is still its own before doing anything, and it completes the row with the same check. A slow original that was taken over finds the counter changed and rolls back, so two attempts can never both post, whatever the clocks say. The lease only decides *when* takeover is allowed.

A **reconciler** (periodic job) closes stale `IN_PROGRESS` rows as `ABORTED` and raises an alert for any anomaly that should be impossible: an API-created transfer with no `COMPLETED` record, a `COMPLETED` `201` record with no transfer, or a transfer stuck in `RECEIVED`, `VALIDATING` or `PROCESSING`.

### 4c. Configuration (all values are defaults to confirm)

| Property | Default | Rule |
|----------|---------|------|
| `funds.idempotency.replay-retention` | 7 days | How long a response is replayable. Stored per row at completion, so changing it is not retroactive |
| `funds.idempotency.lease` | 60 s | Must exceed the request deadline (10 s, D3). A lease that is too short only causes needless takeovers (a performance issue); the fencing token means it can never cause a double posting |
| `funds.idempotency.tombstone-retention` | 90 days | For records with no transfer. Must be >= `replay-retention`; startup fails otherwise |
| `funds.idempotency.in-progress-retry-after` | 1 s | Value of `Retry-After` |

### 4d. Decisions (I1-I8; I5 is optional)

| # | Decision | Proposal |
|---|----------|----------|
| I1 | What retention purges | The response body only; the key record stays |
| I2 | Answer after the window | `409 IDEMPOTENCY_KEY_EXPIRED` (a new code; all 409s, so no new status) with `Location` when a transfer exists |
| I3 | Configuration | Table 4c |
| I4 | Recovery | Lease + fencing token + `ABORTED` state + reconciler |
| I5 | **Optional hardening**: require client keys to be UUIDv7 and reject keys whose embedded time is older than a maximum age (400) | Not adopted. It would close the residual limit in 4a but is a new client constraint (today: any UUID) |
| I6 | Replayed error bodies | Regenerate `correlationId` and `timestamp`; everything else verbatim. Keeps body and header consistent |
| I7 | Payload canonicalisation | As above |
| I8 | Completed records frozen | Database trigger (`TRG_IDEMP_COMPLETED_IMMUTABLE`) |

## 5. Request / response / error examples

Full examples live in the OpenAPI file. Summary:

```
POST /api/v1/transfers
Idempotency-Key: 5b0c3f7e-8a1d-4c2b-9e6f-1a2b3c4d5e6f
{"sourceAccountId":"ACC001","destinationAccountId":"ACC002","amount":"250.00","currency":"USD","reference":"Invoice payment"}

201 Created
Location: /api/v1/transfers/0b6f4e1e-3c1a-4d0e-9b53-6c8f2a1d7e90
X-Correlation-Id: 4f1c2b7a-...
{"transferId":"0b6f4e1e-...","sourceAccountId":"ACC001","destinationAccountId":"ACC002",
 "amount":"250.00","currency":"USD","status":"COMPLETED",
 "createdAt":"2026-09-18T10:15:30.123Z","updatedAt":"2026-09-18T10:15:30.410Z"}
```

| Scenario | Status | `code` |
|----------|--------|--------|
| Bad JSON, missing/invalid key, bad path ID, amount scale > 2 (USD) | 400 | `INVALID_REQUEST` |
| No/expired token | 401 | `UNAUTHENTICATED` |
| Operator tries to create; destination-only owner cancels | 403 | `FORBIDDEN` |
| Not owned or absent transfer/account | 404 | `NOT_FOUND` |
| Same key, different body | 409 | `IDEMPOTENCY_KEY_REUSED` |
| Same key, still in flight | 409 | `IDEMPOTENCY_IN_PROGRESS` |
| Same key, replay window elapsed | 409 | `IDEMPOTENCY_KEY_EXPIRED` |
| Cancel a `PROCESSING`/`COMPLETED` transfer | 409 | `TRANSFER_NOT_CANCELLABLE` |
| Not enough available funds | 422 | `INSUFFICIENT_FUNDS` |
| Inactive or unknown destination / inactive source | 422 | `ACCOUNT_INACTIVE` |
| Currency differs from an account's currency | 422 | `CURRENCY_MISMATCH` |
| Source equals destination | 422 | `SAME_ACCOUNT` |
| Amount is zero | 422 | `AMOUNT_NOT_POSITIVE` |
| Over a transfer limit | 422 | `LIMIT_EXCEEDED` |
| Risk rejection (no detail exposed) | 422 | `TRANSFER_REJECTED` |
| Unexpected failure / server timeout | 500 | `INTERNAL_ERROR` |

## 5a. Response headers

Every response documents the same base header; the rest appear only where they apply. Each header is defined once (a component in `common/` or `transfers/`) and referenced, so wording cannot drift between endpoints. A lint rule (`response-contains-header` in `openapi/redocly.yaml`) fails the build if any 2xx, 4xx or 5xx response omits `X-Correlation-Id`.

| Header | Sent on | Notes |
|--------|---------|-------|
| `X-Correlation-Id` | **Every response**, all 30 documented (success and error) | Echoes a valid inbound value, else server-generated. Error bodies repeat it in `correlationId` |
| `Location` | `201` from `POST /transfers`; `409 IDEMPOTENCY_KEY_EXPIRED` when the original created a transfer | URL of the transfer |
| `Idempotent-Replayed: true` | `201` and `422` from `POST /transfers`, only when a stored outcome is replayed | Absent on the first response. Contract: all stored `201` and `422` outcomes replay verbatim |
| `Retry-After` (seconds) | `409` with code `IDEMPOTENCY_IN_PROGRESS` only | Absent on `IDEMPOTENCY_KEY_REUSED` and on every other status |

Proposed, **not added** (each is a new wire commitment; needs your approval):

| # | Header | Where | Why |
|---|--------|-------|-----|
| H1 | `WWW-Authenticate: Bearer` | Every `401` | RFC 9110 says a `401` must carry a challenge. Spring Security's resource server sends it by default, so documenting it matches likely behaviour |
| H2 | `Cache-Control: no-store` | Balance, transfer and history `200` responses | Keeps financial data out of shared and browser caches |

## 6. Domain relationships

| Relationship | Cardinality | Rule |
|--------------|-------------|------|
| Customer -> Account | 1:N | Account has exactly one owning customer in V1. |
| Customer -> User | 1:N | Users act for their customer. Signatory access is deferred (D2). |
| Account -> AccountBalance | 1:1 | Balance row is the lock target for posting; carries `VERSION`. |
| Account -> Transfer | 1:N as source, 1:N as destination | Source != destination. |
| Transfer -> LedgerEntry | 1:N | A successful transfer has exactly two entries (one DEBIT, one CREDIT), equal amounts. |
| Transfer -> IdempotencyRecord | 1:1 | For API-created transfers; unique per (user, key). |
| Transfer -> RiskAssessment | 1:N | Reassessment on review is allowed. Never exposed via API. |
| Transfer -> AuditEvent | 1:N | Immutable. |
| Transfer -> OutboxEvent | 1:N | Independent publication lifecycle, at-least-once. |
| Transfer -> ReconciliationRecord | 0:N | One per reconciliation run that examines it. |
| Transfer -> Transfer (`reversal_of`) | 0:1 | A reversal is a new transfer linked to the original. |

## 6a. Amendment A1: limit enforcement is concurrency-safe (v1.0.1)

Found in review: the schema stored limits but nothing serialized cumulative usage, so two concurrent transfers could each pass a daily-limit check and together exceed it. Reproduced on a test database (1200 posted against a limit of 1000).

Contract effect, deliberately small:
- The limit check at validation step 7 is now an **advisory pre-check** without locks.
- The **authoritative** check repeats at step 9 inside the posting transaction, under locks, immediately before the funds check. The relative order (limits, then funds) is unchanged.
- Lock order for every posting: source account's customer row, then both balance rows in ascending account-ID order.
- No status code, error code, header or field changes. Full design in `docs/milestone-2-erd.md` section 10.

## 7. Exit-gate checklist

| Item | State |
|------|-------|
| OpenAPI contract | Approved (v1.0.0) |
| Authorization matrix | Approved |
| State-transition table | Approved |
| Request/response/error examples | Approved |
| Domain relationships | Approved |
| Decisions D1-D7 | Approved as proposed |

Follow-ups carried into Milestone 2 (not blocking approval):
- Consider auditing every OPERATOR read/cancel in `AUDIT_EVENTS` (D2 risk).
- Pick one UUID storage type (`RAW(16)` or `VARCHAR2(36)`) before any migration.
- Timeout values (D3) are still placeholders to confirm under load testing.
