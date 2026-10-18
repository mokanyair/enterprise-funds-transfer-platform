# Release 2 - Beneficiaries: API Contract & Domain Design

Status: **APPROVED v1.2.0 (2026-10-18).** Decisions B1-B8 approved as proposed. ERD and migration V7 done (`docs/milestone-2-erd.md` section 12). Service and endpoints implemented and tested (unit, web layer, Oracle IT). UI not started.
Builds on the approved V1 contract (`docs/milestone-1-contract.md`) and ERD (`docs/milestone-2-erd.md`).

Machine-readable contract: `application/funds-transfer-service/src/main/resources/openapi/beneficiaries/beneficiaries-v1.yaml`, wired into the root spec. All six specs pass `scripts/openapi.sh lint`.

## 0. Release 2 sequencing

The frontend guide (section 44) scopes Release 2 as Beneficiaries, Payments, Statements, Notifications and Security Center. Its phase 9 rule says UI work starts only once a backend endpoint contract exists, and none of these has one yet. Each module therefore goes through the same gates V1 did: **contract -> ERD/data dictionary -> additive Flyway migration -> service + tests -> UI**.

| Order | Module | Why this position | Backend need |
|-------|--------|-------------------|--------------|
| 1 | **Beneficiaries** (this document) | Plugs straight into the existing transfer form; smallest new domain | 1 table, 4 endpoints |
| 2 | Statements | Read-only, built on `LEDGER_ENTRIES` + `IX_LEDGER_ACCOUNT` (already indexed "for statements") | Period query + document format decision |
| 3 | Notifications | Can be fed from the existing outbox events | Notification store + read/unread state |
| 4 | Security Center | Mostly Keycloak (MFA, sessions, devices); little or no service work | Keycloak account API / login events |
| 5 | Payments | Bill payment needs external settlement, which V1 excludes; scheduling overlaps Release 3 recurring transfers | Scope decision first |

Unchanged in Release 2: internal, same-currency (USD) transfers only. `POST /transfers` is not modified.

## 1. Endpoints

| Method / path | Purpose | Success |
|---------------|---------|---------|
| `GET /beneficiaries` | List the customer's active beneficiaries | 200 |
| `POST /beneficiaries` | Add one (`accountId`, `nickname`) | 201 + `Location` |
| `GET /beneficiaries/{beneficiaryId}` | Read one | 200 |
| `DELETE /beneficiaries/{beneficiaryId}` | Soft-delete one | 204 (idempotent) |

```
POST /api/v1/beneficiaries
{"accountId":"ACC002","nickname":"Rent - landlord"}

201 Created
Location: /api/v1/beneficiaries/01928f4e-7a1c-7d0e-9b53-6c8f2a1d7e90
{"beneficiaryId":"01928f4e-7a1c-7d0e-9b53-6c8f2a1d7e90","accountId":"ACC002",
 "nickname":"Rent - landlord","createdAt":"2026-10-18T09:00:00.000Z"}
```

## 2. Decisions (B1-B8, approved as proposed 2026-10-18)

| # | Decision | Proposal | Why it matters / alternative |
|---|----------|----------|------------------------------|
| B1 | Transfer integration | **Transfers do not reference beneficiaries.** The UI fills `destinationAccountId` from the selected beneficiary; `POST /transfers` is unchanged and re-validates the destination every time. | The approved transfer contract, idempotency hash and ledger stay untouched. Alternative (`beneficiaryId` on the transfer) changes the approved request schema and the hash canonicalisation. |
| B2 | Ownership scope | Beneficiaries belong to the **customer**, not the user. All users of a customer share one list; `CREATED_BY_USER_ID` records who added it. | Matches accounts (customer-owned, D2). Alternative: per-user lists. |
| B3 | Retry safety without `Idempotency-Key` | `UNIQUE` active (customer, account) pair. A retried create returns `409 BENEFICIARY_ALREADY_EXISTS` + `Location` of the existing row; the UI treats that as success. | Nothing financial is posted, so the full idempotency machinery (A2) is not justified. Cost: a retry after a lost 201 sees 409, not a replay. |
| B4 | Disclosure | Add validates the account exactly like a transfer destination (unknown = inactive = `422 ACCOUNT_INACTIVE`). The response never contains the destination's holder name, status or currency. **No confirmation-of-payee name lookup.** | Discloses nothing `POST /transfers` does not already. But add is cheaper to call than a transfer, which B5 limits. Payee-name lookup is a separate privacy decision. |
| B5 | Caps | At most **50 active** beneficiaries per customer and **20 adds per customer per rolling 24 h** -> `422 BENEFICIARY_LIMIT_REACHED`. Checked under the customer-row lock. | Bounds the list (no pagination needed) and limits account probing through add/delete loops. Values are placeholders, like D3. |
| B6 | Own accounts | Saving one of the customer's own accounts -> `422 BENEFICIARY_OWN_ACCOUNT`. | The transfer form already lists own accounts as destinations. Alternative: allow it silently. |
| B7 | Delete semantics | Soft delete (`STATUS = DELETED`, `DELETED_AT`, `DELETED_BY_USER_ID`). Repeating a delete returns 204. The same account can be re-added later as a new row. | Runtime has no `DELETE` privilege (P8); keeps an audit trail. |
| B8 | Operators | All four endpoints are `403` for `OPERATOR`. Operator read access to a customer's beneficiaries is out of Release 2. | Matches `GET /accounts`. A support view needs a customer-scoped path and `OPERATOR_READ` auditing, so it is a separate design. |

Out of scope: editing a nickname (delete and re-add instead), external or cross-currency beneficiaries, payee-name confirmation, and step-up MFA when adding a beneficiary. Step-up is a common control for this flow and is worth considering together with Security Center.

## 3. Authorization matrix

| Operation | CUSTOMER, own customer's beneficiary | CUSTOMER, another customer's | OPERATOR | Unauthenticated |
|-----------|--------------------------------------|------------------------------|----------|-----------------|
| `GET /beneficiaries` | Allowed (own list only) | n/a | 403 | 401 |
| `POST /beneficiaries` | Allowed | n/a | 403 | 401 |
| `GET /beneficiaries/{id}` | Allowed (404 if deleted) | 404 | 403 | 401 |
| `DELETE /beneficiaries/{id}` | Allowed (204 if already deleted) | 404 | 403 | 401 |

As in V1: identity comes from the token only. Ownership is in the query (`WHERE beneficiary_id = ? AND customer_id = <caller's>`). Another customer's ID and an unknown ID produce an identical 404.

## 4. Validation order for `POST /beneficiaries` (first failure wins)

1. Schema -> `400 INVALID_REQUEST`. Unknown fields are rejected. `nickname` is trimmed, must be 1-40 characters, no control characters.
2. `accountId` is one of the caller's own accounts -> `422 BENEFICIARY_OWN_ACCOUNT` (B6).
3. Account exists, is `ACTIVE`, currency supported -> otherwise `422 ACCOUNT_INACTIVE` (B4).
4. Lock the customer row (the same lock target posting uses, A1). Then, under the lock:
   - already an active beneficiary -> `409 BENEFICIARY_ALREADY_EXISTS` + `Location`;
   - 50 active, or 20 adds in the last 24 h -> `422 BENEFICIARY_LIMIT_REACHED`.
5. Insert, write the audit row, commit.

A transfer to a beneficiary whose account has since been frozen or closed fails at transfer time with the existing `422 ACCOUNT_INACTIVE`. The beneficiary stays listed (B4: its status is not exposed).

## 5. Errors

| Scenario | Status | `code` |
|----------|--------|--------|
| Bad JSON, unknown field, bad nickname, bad path UUID | 400 | `INVALID_REQUEST` |
| No/expired token | 401 | `UNAUTHENTICATED` |
| Operator calls any endpoint | 403 | `FORBIDDEN` |
| Unknown, deleted (GET) or other customer's beneficiary | 404 | `NOT_FOUND` |
| Account already saved | 409 | `BENEFICIARY_ALREADY_EXISTS` **(new)** |
| Own account | 422 | `BENEFICIARY_OWN_ACCOUNT` **(new)** |
| Unknown or non-active account | 422 | `ACCOUNT_INACTIVE` |
| Over a B5 cap | 422 | `BENEFICIARY_LIMIT_REACHED` **(new)** |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

The three new codes are added to the closed `ErrorCode` set in `common/` (version 1.2.0; additive, no existing code changed). The `common` description now states that clients must tolerate unknown codes. The banking UI already does this: it displays the server's safe `message` and switches only on the idempotency codes. Its `ApiErrorCode` type union gains the three values when the Beneficiaries screen is built.

Headers: `X-Correlation-Id` on every response (lint-enforced). `Location` on 201 and 409. `Cache-Control: no-store` on both GET 200s. This is a new endpoint, so it is not the open H2 question for the V1 endpoints, though it follows H2's reasoning.

## 6. Domain and proposed physical design (for the ERD step)

| Relationship | Cardinality | Rule |
|--------------|-------------|------|
| Customer -> Beneficiary | 1:N | At most 50 active (B5) |
| Account -> Beneficiary | 1:N | Destination account. At most one *active* per customer |
| AppUser -> Beneficiary | 1:N | Creator; deleter when deleted |
| Beneficiary -> AuditEvent | 1:N | `BENEFICIARY_ADD`, `BENEFICIARY_DELETE`, `ENTITY_TYPE = 'BENEFICIARY'` |

Table **BENEFICIARIES** (13th table), created by `V7__create_beneficiaries.sql`. The authoritative physical design is `docs/milestone-2-erd.md` section 12; it adds the trigger `TRG_BENEF_IMMUTABLE` and the nickname CHECK to this outline.

| Column | Type | Null | Notes |
|--------|------|------|-------|
| BENEFICIARY_ID | RAW(16) | NN | PK, UUIDv7 (P2) |
| CUSTOMER_ID | RAW(16) | NN | FK -> CUSTOMERS |
| ACCOUNT_ID | RAW(16) | NN | FK -> ACCOUNTS (internal key, P1); API shows `ACCOUNT_NUMBER` |
| NICKNAME | VARCHAR2(40 CHAR) | NN | Untrusted text |
| STATUS | VARCHAR2(10) | NN | CHECK IN (`ACTIVE`,`DELETED`) |
| CREATED_BY_USER_ID | RAW(16) | NN | FK -> APP_USERS |
| CREATED_AT | TIMESTAMP WITH TIME ZONE | NN | |
| DELETED_BY_USER_ID | RAW(16) | | FK -> APP_USERS |
| DELETED_AT | TIMESTAMP WITH TIME ZONE | | CHECK: both deleted columns set exactly when `DELETED` |

- Active uniqueness: function-based unique index `UX_BENEF_ACTIVE` on `(CASE WHEN STATUS='ACTIVE' THEN CUSTOMER_ID END, CASE WHEN STATUS='ACTIVE' THEN ACCOUNT_ID END)`. Deleted rows produce all-NULL keys, which Oracle does not index, so re-adding after a delete works.
- `IX_BENEF_CUSTOMER(CUSTOMER_ID, CREATED_AT)` serves the list and the 24 h add-count.
- Runtime grants: `SELECT, INSERT, UPDATE` (status change only). No `DELETE` (P8).
- Retention and sizing: small (at most 50 active rows per customer, plus deleted history). The retention period for deleted rows needs a decision at the ERD step.

## 7. Tests required at implementation

- Authorization matrix: every cell, including identical 404s for foreign and unknown IDs.
- Each validation step and its order; nickname trimming and control-character rejection.
- Concurrency: parallel adds of the same account create exactly one row, and parallel adds at 49 active end at exactly 50.
- Delete is idempotent; re-adding after a delete succeeds; a deleted beneficiary is absent from the list and its GET returns 404.
- Privilege test: `FUNDS_APP` cannot `DELETE FROM BENEFICIARIES`.
- No response leaks destination holder, status or currency.

## 8. Exit-gate checklist

| Item | State |
|------|-------|
| OpenAPI contract (`beneficiaries-v1.yaml`, v1.2.0) | Approved, lints clean |
| Authorization matrix | Approved |
| Validation order and error table | Approved |
| Decisions B1-B8 | Approved as proposed |
| ERD / data dictionary / migration V7 | Done, verified on a disposable Oracle (ERD section 12) |
| Service, endpoints, tests (section 7) | Done: `BeneficiaryService`, `BeneficiariesController`; 32 service + 12 web-layer unit tests, 9 Oracle ITs, all pass |
| Banking UI screen | Not started |

Housekeeping in this change: the root spec now also wires `GET /accounts`. It was already defined in `accounts-v1.yaml` and implemented, but missing from the root. All spec versions were bumped to 1.2.0.
