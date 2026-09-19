# funds-transfer-service

Internal, same-currency account-to-account transfers. Spring Boot 3.5, Java 21, Maven, Oracle (plain JDBC).
Implements the approved contract in `src/main/resources/openapi/` and the design in `docs/`.

## Status

| Area | State |
|------|-------|
| Compiles, packages | Yes (JDK 21) |
| 83 unit and web-layer tests | **Pass** |
| 19 Oracle integration tests (`*IT`) | Written, **never run** (they need a database; they skip themselves) |
| SQL in the repositories, the V1-V6 migrations | **Never executed against Oracle** since the last edits |
| Application start-up | Not attempted (needs a database and an identity provider) |

Treat the persistence layer as untested until the integration tests have passed once.

## Build and test

Needs **JDK 21** and Maven 3.6.3 or newer.

```bash
mvn test        # unit + web-layer tests: no database, no Docker, no network services
mvn verify      # also runs *IT classes, which skip unless FUNDS_IT_ENABLED=true
```

## Configuration

Everything comes from the environment; nothing secret is in `application.yml`, and the service refuses to start if a
required value is missing. See `.env.example` for the names (values are never committed).

| Variable | Purpose |
|----------|---------|
| `FUNDS_DB_URL` | e.g. `jdbc:oracle:thin:@//<host>:1521/FREEPDB1` |
| `FUNDS_DB_USER` / `FUNDS_DB_PASSWORD` | **Runtime identity** (`FUNDS_APP`): DML and SELECT only |
| `FUNDS_DB_SCHEMA` | Schema that owns the tables (default `FUNDS_OWNER`) |
| `FUNDS_JWT_ISSUER_URI` | Identity provider issuer; tokens are verified against it |
| `FLYWAY_ENABLED`, `FUNDS_MIGRATION_DB_USER`, `FUNDS_MIGRATION_DB_PASSWORD` | Migrations run as a **separate** identity (`FUNDS_OWNER`), off by default |

Tunables (`funds.*`, defaults in `application.yml`): request deadline, lock wait, idempotency replay retention,
lease, tombstone retention, risk thresholds, outbox publisher, background jobs. Start-up fails on unsafe combinations
(for example a tombstone retention shorter than the replay retention).

## Endpoints

| Method and path | Spec |
|-----------------|------|
| `POST /api/v1/transfers` (needs `Idempotency-Key`) | `openapi/transfers/transfers-v1.yaml` |
| `GET /api/v1/transfers/{id}`, `POST /api/v1/transfers/{id}/cancel` | same |
| `GET /api/v1/accounts/{id}/balance`, `GET /api/v1/accounts/{id}/transfers` | `openapi/accounts/accounts-v1.yaml` |
| `GET /actuator/health` | no token needed |

## How it fits together

```
web/        controllers, error body, correlation-id filter          thin: no business rules
security/   verified token subject -> APP_USERS -> Actor             identity is never client-supplied
service/    TransferService (create/get/cancel), IdempotencyService, PostingService (the only place money moves),
            LimitEvaluator, AccountService, ReviewService
persistence/ plain JDBC repositories (RAW(16) ids, UUIDv7)
jobs/       idempotency reconciler + body purge, outbox publisher (Kafka, off by default)
domain/     state machine, error codes, currency policy
```

Posting takes locks in a fixed order: idempotency row, source customer row, balance rows in ascending account id.
Limits and funds are checked under those locks. See `docs/milestone-2-erd.md` sections 10 and 11.

## Running the Oracle integration tests

Use a **disposable** database. The audit table is immutable, so test data cannot be cleaned up.

```bash
export FUNDS_IT_ENABLED=true
export FUNDS_DB_URL=... FUNDS_DB_USER=FUNDS_APP FUNDS_DB_PASSWORD=...      # runtime identity
export FUNDS_IT_OWNER_USER=FUNDS_OWNER FUNDS_IT_OWNER_PASSWORD=...          # creates fixtures
mvn verify
```

`PostingIT` covers accounting, rollback, replay and expiry, concurrency, limit races, deadlock, recovery, review and
cancel. `PrivilegeIT` proves the runtime identity cannot run DDL, delete, rewrite the ledger or audit trail, or read
other schemas. The runtime grants are provisioning, not migrations: see the privileges matrix in `docs/milestone-2-erd.md`.

## Known limits

- **Reversals** are supported in the data model (`REVERSAL_OF`) and in limit accounting, but the V1 API exposes no reversal endpoint.
- **Manual review** (`ReviewService`) has no HTTP endpoint, because the contract defines none.
- **Tombstone purge** (deleting old idempotency records that have no transfer) is not in the app: the runtime identity has no `DELETE`.
- **Reconciliation** of ledger against transfers is not implemented here yet.
