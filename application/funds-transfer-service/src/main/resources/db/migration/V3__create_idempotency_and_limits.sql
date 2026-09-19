-- V3: idempotency and transfer limits
-- Tables: IDEMPOTENCY_RECORDS (+ immutability trigger), TRANSFER_LIMITS

-- One row per (actor, Idempotency-Key). Design: docs/milestone-2-erd.md section 11.
--
-- Lifecycle:  IN_PROGRESS -> COMPLETED            (posting or a stored 422 committed)
--             IN_PROGRESS -> ABORTED              (attempt failed or was reconciled; nothing posted)
--             ABORTED / lease-expired IN_PROGRESS -> IN_PROGRESS   (retry takes over, ATTEMPT + 1)
-- COMPLETED is terminal. A completed row is never deleted by the runtime user, so a key stays
-- remembered after its response stops being replayable; that is what stops an expired key
-- from causing a second posting. Only the response body is purged (RESPONSE_PURGED_AT).
--
-- The unique constraint serialises concurrent retries: the loser of the insert race reads
-- the winner's row. TRANSFER_ID is nullable because most 422 outcomes never create a
-- transfer, yet are stored and replayed. UNIQUE still gives 1:1 where a transfer exists
-- (Oracle allows many NULLs in a single-column unique index).
CREATE TABLE IDEMPOTENCY_RECORDS (
    ID                  RAW(16)                  NOT NULL,
    USER_ID             RAW(16)                  NOT NULL,
    IDEMPOTENCY_KEY     RAW(16)                  NOT NULL,
    REQUEST_HASH        RAW(32)                  NOT NULL,
    STATUS              VARCHAR2(12)             NOT NULL,
    -- Fencing token: incremented each time a retry takes over an abandoned attempt. The
    -- posting transaction re-checks it under lock, so a superseded attempt cannot commit.
    ATTEMPT             NUMBER(5)                DEFAULT 1 NOT NULL,
    -- Set only while IN_PROGRESS; NULL otherwise (keeps IX_IDEMP_LEASE small).
    LEASE_EXPIRES_AT    TIMESTAMP WITH TIME ZONE,
    TRANSFER_ID         RAW(16),
    RESPONSE_STATUS     NUMBER(3),
    RESPONSE_BODY       CLOB,
    CREATED_AT          TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    COMPLETED_AT        TIMESTAMP WITH TIME ZONE,
    -- Set at completion from the configured retention. NULL once the body is purged.
    REPLAY_EXPIRES_AT   TIMESTAMP WITH TIME ZONE,
    RESPONSE_PURGED_AT  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_IDEMPOTENCY_RECORDS  PRIMARY KEY (ID),
    CONSTRAINT FK_IDEMP_USER           FOREIGN KEY (USER_ID)     REFERENCES APP_USERS (USER_ID),
    CONSTRAINT FK_IDEMP_TRANSFER       FOREIGN KEY (TRANSFER_ID) REFERENCES TRANSFERS (TRANSFER_ID),
    CONSTRAINT UK_IDEMP_USER_KEY       UNIQUE (USER_ID, IDEMPOTENCY_KEY),
    CONSTRAINT UK_IDEMP_TRANSFER       UNIQUE (TRANSFER_ID),
    CONSTRAINT CK_IDEMP_STATUS         CHECK (STATUS IN ('IN_PROGRESS', 'COMPLETED', 'ABORTED')),
    CONSTRAINT CK_IDEMP_ATTEMPT        CHECK (ATTEMPT >= 1),
    CONSTRAINT CK_IDEMP_RESP_STATUS    CHECK (RESPONSE_STATUS IS NULL OR RESPONSE_STATUS BETWEEN 100 AND 599),
    CONSTRAINT CK_IDEMP_RESP_JSON      CHECK (RESPONSE_BODY IS JSON),
    -- A lease exists exactly while the attempt is in progress.
    CONSTRAINT CK_IDEMP_LEASE          CHECK (
        (STATUS = 'IN_PROGRESS' AND LEASE_EXPIRES_AT IS NOT NULL)
        OR (STATUS <> 'IN_PROGRESS' AND LEASE_EXPIRES_AT IS NULL)
    ),
    -- Only a completed record carries an outcome. It must record the status code and either
    -- the body (replayable) or the fact that the body was purged.
    CONSTRAINT CK_IDEMP_COMPLETED      CHECK (
        STATUS <> 'COMPLETED'
        OR (RESPONSE_STATUS IS NOT NULL AND COMPLETED_AT IS NOT NULL
            AND (RESPONSE_BODY IS NOT NULL OR RESPONSE_PURGED_AT IS NOT NULL))
    ),
    CONSTRAINT CK_IDEMP_NO_OUTCOME     CHECK (
        STATUS = 'COMPLETED'
        OR (TRANSFER_ID IS NULL AND RESPONSE_STATUS IS NULL AND RESPONSE_BODY IS NULL
            AND COMPLETED_AT IS NULL AND REPLAY_EXPIRES_AT IS NULL AND RESPONSE_PURGED_AT IS NULL)
    ),
    -- Replayable only while the body is still stored; a purged body is gone.
    CONSTRAINT CK_IDEMP_REPLAY         CHECK (REPLAY_EXPIRES_AT IS NULL OR RESPONSE_PURGED_AT IS NULL),
    CONSTRAINT CK_IDEMP_PURGED         CHECK (RESPONSE_PURGED_AT IS NULL OR RESPONSE_BODY IS NULL)
);

-- Defence in depth: once COMPLETED, the request hash, actor, key, transfer and outcome status
-- can never change, so no bug can re-point a finished key at a second posting. The only allowed
-- change is purging the stored body after retention (RESPONSE_BODY, RESPONSE_PURGED_AT,
-- REPLAY_EXPIRES_AT). Does not stop DELETE; that is governed by privileges and the retention job.
CREATE TRIGGER TRG_IDEMP_COMPLETED_IMMUTABLE
BEFORE UPDATE ON IDEMPOTENCY_RECORDS
FOR EACH ROW
WHEN (OLD.STATUS = 'COMPLETED')
BEGIN
    IF :NEW.STATUS <> 'COMPLETED'
       OR :NEW.USER_ID <> :OLD.USER_ID
       OR :NEW.IDEMPOTENCY_KEY <> :OLD.IDEMPOTENCY_KEY
       OR :NEW.REQUEST_HASH <> :OLD.REQUEST_HASH
       OR :NEW.ATTEMPT <> :OLD.ATTEMPT
       OR :NEW.RESPONSE_STATUS <> :OLD.RESPONSE_STATUS
       OR :NEW.COMPLETED_AT <> :OLD.COMPLETED_AT
       OR NVL(:NEW.TRANSFER_ID, HEXTORAW('00')) <> NVL(:OLD.TRANSFER_ID, HEXTORAW('00'))
    THEN
        RAISE_APPLICATION_ERROR(-20002,
            'A completed idempotency record is immutable; only its stored response may be purged');
    END IF;
END;
/

-- Limits are changed by inserting a new row, never by editing an old one.
CREATE TABLE TRANSFER_LIMITS (
    LIMIT_ID        RAW(16)                  NOT NULL,
    CUSTOMER_ID     RAW(16),
    ACCOUNT_ID      RAW(16),
    PERIOD          VARCHAR2(12)             NOT NULL,
    AMOUNT          NUMBER(19,4)             NOT NULL,
    CURRENCY        VARCHAR2(3)              NOT NULL,
    EFFECTIVE_FROM  TIMESTAMP WITH TIME ZONE NOT NULL,
    EFFECTIVE_TO    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_TRANSFER_LIMITS   PRIMARY KEY (LIMIT_ID),
    CONSTRAINT FK_LIMITS_CUSTOMER   FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMERS (CUSTOMER_ID),
    CONSTRAINT FK_LIMITS_ACCOUNT    FOREIGN KEY (ACCOUNT_ID)  REFERENCES ACCOUNTS (ACCOUNT_ID),
    CONSTRAINT CK_LIMITS_PERIOD     CHECK (PERIOD IN ('PER_TRANSFER', 'DAILY', 'MONTHLY')),
    CONSTRAINT CK_LIMITS_AMOUNT     CHECK (AMOUNT > 0),
    CONSTRAINT CK_LIMITS_CURRENCY   CHECK (CURRENCY IN ('USD')),
    -- Exactly one scope: customer-wide or single account.
    CONSTRAINT CK_LIMITS_SCOPE      CHECK (
        (CUSTOMER_ID IS NOT NULL AND ACCOUNT_ID IS NULL)
        OR (CUSTOMER_ID IS NULL AND ACCOUNT_ID IS NOT NULL)
    ),
    CONSTRAINT CK_LIMITS_DATES      CHECK (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO > EFFECTIVE_FROM)
);
