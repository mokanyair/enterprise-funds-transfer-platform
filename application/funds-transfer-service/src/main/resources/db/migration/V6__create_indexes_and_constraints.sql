-- V6: secondary indexes
-- Only indexes that are not already provided by a PK or UNIQUE constraint.
-- Rule: an FK column is indexed only if it is queried or its parent rows can be
-- deleted; in V1 no parent row is ever deleted.
-- Deliberately absent: LEDGER_ENTRIES(TRANSFER_ID), covered by UK_LEDGER_POSITION.
-- Deliberately absent: TRANSFERS(CREATED_BY_USER_ID), never queried in V1.
-- No forward-referencing FKs exist, so every constraint is already created inline
-- in V1-V5 and this script holds indexes only.

CREATE INDEX IX_TRANSFERS_SRC       ON TRANSFERS (SOURCE_ACCOUNT_ID, CREATED_AT);
CREATE INDEX IX_TRANSFERS_DST       ON TRANSFERS (DESTINATION_ACCOUNT_ID, CREATED_AT);
CREATE INDEX IX_LEDGER_ACCOUNT      ON LEDGER_ENTRIES (ACCOUNT_ID, POSTED_AT);
CREATE INDEX IX_OUTBOX_STATUS       ON OUTBOX_EVENTS (STATUS, CREATED_AT);
CREATE INDEX IX_OUTBOX_TRANSFER     ON OUTBOX_EVENTS (TRANSFER_ID);
CREATE INDEX IX_AUDIT_ENTITY        ON AUDIT_EVENTS (ENTITY_ID, CREATED_AT);
CREATE INDEX IX_APP_USERS_CUSTOMER  ON APP_USERS (CUSTOMER_ID);
CREATE INDEX IX_ACCOUNTS_CUSTOMER   ON ACCOUNTS (CUSTOMER_ID);
CREATE INDEX IX_LIMITS_CUSTOMER     ON TRANSFER_LIMITS (CUSTOMER_ID);
CREATE INDEX IX_LIMITS_ACCOUNT      ON TRANSFER_LIMITS (ACCOUNT_ID);
CREATE INDEX IX_RISK_TRANSFER       ON RISK_ASSESSMENTS (TRANSFER_ID);
CREATE INDEX IX_RECON_TRANSFER      ON RECONCILIATION_RECORDS (TRANSFER_ID);
-- Idempotency maintenance. Both columns are NULL except while a row is IN_PROGRESS (lease) or
-- COMPLETED with a stored body (replay expiry), so Oracle's B-tree holds only those few rows and
-- the recovery and purge jobs range-scan instead of reading the whole table.
CREATE INDEX IX_IDEMP_LEASE         ON IDEMPOTENCY_RECORDS (LEASE_EXPIRES_AT);
CREATE INDEX IX_IDEMP_REPLAY_EXPIRY ON IDEMPOTENCY_RECORDS (REPLAY_EXPIRES_AT);
