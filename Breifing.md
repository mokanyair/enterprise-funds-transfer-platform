Briefing: Enterprise Funds Transfer Platform — Milestones 1–3
Paste this whole file into your new Claude Code session (in VS Code, WSL, rooted at
`/home/ubuntu/projects/enterprise-funds-transfer-platform`) as the first message, so it has
full context without needing the original Word doc.
Source spec
Implementation blueprint | Milestones 1–3 | v1.0 | 18 Sept 2026.
Purpose: approve the API/domain contracts, derive the Oracle logical/physical design, then
implement and verify version-controlled Flyway migrations. This is a plan, not a claim that
tables/migrations already exist.
Scope & prerequisites
V1: internal, same-currency account-to-account transfers only. No external settlement or FX.
Oracle FREE / FREEPDB1 on bank-db-01 (10.30.21.175:1521); app-to-listener TCP already validated.
JDBC: `jdbc:oracle:thin:@//10.30.21.175:1521/FREEPDB1`
Before writing any files: inspect the actual repo — build tool, Java/Spring Boot version,
existing entities/migrations — and reconcile with what's there rather than overwrite. If the
repo is empty, scaffold fresh: Spring Boot 3.x, Maven, Java 21 (adjust if the user has a
preference already reflected in the repo).
Milestone 1 — API contract & domain design
Endpoints
Method / endpoint	Contract
`POST /api/v1/transfers`	Create transfer. Require `Idempotency-Key` header. 201 + `Location`. Identical retry → same resource/result. Mismatched payload under same key → 409.
`GET /api/v1/transfers/{id}`	Status/details; enforce access authorization.
`GET /api/v1/accounts/{id}/balance`	Authorized balance read; must not leak other accounts.
`GET /api/v1/accounts/{id}/transfers`	Authorized, paginated history.
`POST /api/v1/transfers/{id}/cancel`	Only unposted eligible transfers; 409 if already posted.
Request/response
POST body: `{"sourceAccountId":"ACC001","destinationAccountId":"ACC002","amount":"250.00","currency":"USD","reference":"Invoice payment"}`
Header: `Idempotency-Key: <client-generated UUID>`
Identity comes from the verified security context, never a client-supplied user ID.
Response fields: `transferId, sourceAccountId, destinationAccountId, amount, currency, status, createdAt, updatedAt`. Never expose internal risk details or secrets.
Standardize 400/401/403/404/409/422/500 error bodies + correlation ID. Document and test the exact status for in-progress requests.
State machine & invariants
`RECEIVED → VALIDATING → [PENDING_REVIEW] → PROCESSING → COMPLETED`, with terminal alternates
`REJECTED`, `FAILED`, `CANCELLED`. Enforce legal transitions in code + tests.
Kafka publication is an independent outbox state, not a transfer state.
A committed posting is never cancelled in place — a reversal is a new linked transaction.
Validate: ownership/authorization, active accounts, positive amount, currency match, sufficient
available funds, limits, risk decision.
Use `BigDecimal` everywhere; enforce precision/scale and currency minor-unit policy — no floats.
Lock accounts in stable ID order; check balances under lock; atomically update balances, insert
balanced ledger entries, set transfer state, insert outbox event.
Idempotency uniqueness scoped to authenticated actor (or agreed tenant scope); persist request
hash + original outcome; concurrent retries must not double-post.
Explicit timeouts, retry semantics, audit trail; consumers handle at-least-once delivery.
Domain ownership & cardinality
Relationship	Rule
Customer → Account/User	1:N; define signatory access separately if multi-user access needed
Account → Transfer	1:N in both source and destination roles
Transfer → LedgerEntry	1:N; a basic successful transfer has exactly two balanced entries
Transfer → IdempotencyRecord	1:1 for API-created transfers; unique per actor/key
Transfer → RiskAssessment / AuditEvent / OutboxEvent	1:N
Transfer → ReconciliationRecord	0:N across reconciliation runs
Exit gate: approved OpenAPI contract, authorization matrix, state-transition table,
request/response/error examples, domain relationships.
Milestone 2 — Oracle ERD & physical design
Tables (minimum design)
CUSTOMERS — `CUSTOMER_ID` PK; name, status.
APP_USERS — `USER_ID` PK; `CUSTOMER_ID` FK; `IDP_SUBJECT` unique; role/status.
ACCOUNTS — `ACCOUNT_ID` PK; `CUSTOMER_ID` FK; currency/status.
ACCOUNT_BALANCES — `ACCOUNT_ID` PK/FK; available/ledger `NUMBER(19,4)`; `VERSION`.
TRANSFERS — `TRANSFER_ID` PK; source/destination account FKs; amount/currency/status;
timestamps; optional `reversal_of` FK.
LEDGER_ENTRIES — `ENTRY_ID` PK; `TRANSFER_ID`/`ACCOUNT_ID` FKs; side DEBIT/CREDIT; amount;
`entry_position`; `UNIQUE(TRANSFER_ID, entry_position)`.
IDEMPOTENCY_RECORDS — `ID` PK; `USER_ID` FK; key, request hash, transfer FK;
`UNIQUE(USER_ID, key)`.
TRANSFER_LIMITS — `LIMIT_ID` PK; customer/account scope; amount, period, effective dates.
RISK_ASSESSMENTS — `ASSESSMENT_ID` PK; `TRANSFER_ID` FK; decision/reason/timestamp.
OUTBOX_EVENTS — `EVENT_ID` PK; `TRANSFER_ID` FK; type/payload/status; attempts/timestamps.
AUDIT_EVENTS — `AUDIT_ID` PK; actor/action/entity/outcome/timestamp; immutable policy.
RECONCILIATION_RECORDS — `ID` PK; `TRANSFER_ID` FK; expected/actual/status/run timestamp.
(12 tables total — matches the Milestone 3 acceptance criterion.)
Integrity, indexes, access
PK/FK/NOT NULL/CHECK constraints for status enums, positive amounts, supported currency,
distinct source/destination, nonnegative available balances where required.
Money: `NUMBER(19,4)`. Currency: `VARCHAR2(3)`. Timestamps: `TIMESTAMP WITH TIME ZONE`.
Pick one UUID storage representation (`RAW(16)` or `VARCHAR2(36)`) and use it consistently
before migrating.
Indexes: `TRANSFERS(source_account_id, created_at)`, `TRANSFERS(destination_account_id, created_at)`,
`LEDGER_ENTRIES(transfer_id)`, `OUTBOX_EVENTS(status, created_at)`, `AUDIT_EVENTS(entity_id, created_at)`.
The unique idempotency index comes from the unique constraint — don't duplicate it.
Account for FK-supporting indexes and real query plans; avoid redundant indexes.
The cross-row balanced-ledger invariant is checked in the posting service AND independently
reconciled — don't rely on a DB constraint alone for it.
Define retention, expected volume, growth before sizing `FUNDS_DATA`; confirm Oracle Free
limits and free disk. Schema owner (migration principal) and restricted runtime principal are
separate identities.
Exit gate: ERD, data dictionary, approved constraints/indexes, retention/sizing estimate,
privileges matrix, migration ordering.
Milestone 3 — Provision, migrate, validate
Execution order
Inspect — read repo/build config; check existing schemas, tablespaces, Flyway history, DB
disk and PDB open state. Record baseline before touching anything.
Provision — create tablespace + schema owner only if absent; create migration and runtime
identities; passwords go in Secrets Manager, scoped IAM. Never in shell history, SSM output,
Git, or Terraform state.
Author SQL:
`V1__create_identity_and_accounts.sql`
`V2__create_transfers_and_ledger.sql`
`V3__create_idempotency_and_limits.sql`
`V4__create_risk_and_audit.sql`
`V5__create_outbox_and_reconciliation.sql`
`V6__create_indexes_and_constraints.sql`
Include every column/FK/CHECK agreed in Milestone 2.
Configure Flyway — Oracle-compatible dependency/config in the actual build; run with the
migration identity against FREEPDB1; restrict the runtime account to needed DML/SELECT only.
Verify — inspect DBA/ALL metadata + Flyway history/validate; test FK/check/unique
violations, duplicate/concurrent requests, insufficient funds, rollback, ledger balancing.
Deliver — commit SQL, provisioning automation, tests, ERD, runbook, sanitized execution
evidence. Review the git diff for secrets before committing.
Target file layout
```
application/funds-transfer-service/src/main/resources/db/migration/
  V1__create_identity_and_accounts.sql
  V2__create_transfers_and_ledger.sql
  V3__create_idempotency_and_limits.sql
  V4__create_risk_and_audit.sql
  V5__create_outbox_and_reconciliation.sql
  V6__create_indexes_and_constraints.sql
```
Confirm the actual module path first; adjust to real repo conventions. Keep infra provisioning
automation outside Flyway migrations.
Safety & acceptance
Oracle DDL implicitly commits — a failed migration can leave partial objects. Validate on a
disposable clone first; plan forward repair, don't assume DDL rollback.
Use Flyway schema history; never hand-edit an applied migration version. Additive migrations
only after release.
Prove the runtime user cannot `CREATE`/`DROP` tables or read other schemas; prove migrations
can create the required objects.
Validate DB restart/persistence and actual JDBC auth from the app host — separately from raw
TCP connectivity.
Acceptance: 12 designed tables; expected constraints/indexes; `flyway validate` passes;
privilege tests pass; accounting/idempotency/concurrency/rollback tests pass; no secrets in Git.
Current status (as of this handoff)
Milestone 1: contract proposed above, not yet formally approved.
Milestone 2: logical design proposed above, physical SQL not yet written.
Milestone 3: execution plan only — provisioning, migrations, and tests not yet performed.
First things to actually do in this session
Run `git status`, check for a `pom.xml`/`build.gradle`, list any existing Java sources,
entities, and `src/main/resources/db/migration/*`. If the folder is empty, say so and proceed
to scaffold fresh (Spring Boot 3.x + Maven + Java 21, unless told otherwise).
Confirm/adjust the module path (`application/funds-transfer-service/...` above is a proposal,
not fixed).
Draft the OpenAPI contract for the 5 endpoints above (get it reviewed before generating SQL).
Only after the contract is agreed, generate the ERD/data dictionary, then the six Flyway
migration scripts, then configure Flyway in the build and run against FREEPDB1
(`jdbc:oracle:thin:@//10.30.21.175:1521/FREEPDB1`) using a migration identity with least
privilege — ask the user for how they want to manage the DB credentials (env var, Secrets
Manager, `.env` file gitignored, etc.) since that's an environment-specific decision.
Write tests per the acceptance criteria in Milestone 3 before calling anything done.