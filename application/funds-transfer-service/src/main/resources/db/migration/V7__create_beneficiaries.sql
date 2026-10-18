-- V7: beneficiaries (Release 2)
-- Tables: BENEFICIARIES
-- Design: docs/milestone-2-erd.md section 12 and docs/release-2-beneficiaries-contract.md
-- (B1-B8 approved 2026-10-18). Additive only: no existing object is touched.
-- Oracle DDL auto-commits: if this script fails part-way, repair forward, do not edit it.

-- A saved destination for a customer's transfers. Transfers never reference this table (B1),
-- so deleting a beneficiary cannot affect any posting. Rows are soft-deleted (B7): the
-- runtime user has no DELETE privilege (P8).
CREATE TABLE BENEFICIARIES (
    BENEFICIARY_ID      RAW(16)                  NOT NULL,
    CUSTOMER_ID         RAW(16)                  NOT NULL,
    ACCOUNT_ID          RAW(16)                  NOT NULL,
    NICKNAME            VARCHAR2(40 CHAR)        NOT NULL,
    STATUS              VARCHAR2(10)             NOT NULL,
    CREATED_BY_USER_ID  RAW(16)                  NOT NULL,
    CREATED_AT          TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    DELETED_BY_USER_ID  RAW(16),
    DELETED_AT          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_BENEFICIARIES      PRIMARY KEY (BENEFICIARY_ID),
    CONSTRAINT FK_BENEF_CUSTOMER     FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMERS (CUSTOMER_ID),
    CONSTRAINT FK_BENEF_ACCOUNT      FOREIGN KEY (ACCOUNT_ID) REFERENCES ACCOUNTS (ACCOUNT_ID),
    CONSTRAINT FK_BENEF_CREATED_BY   FOREIGN KEY (CREATED_BY_USER_ID) REFERENCES APP_USERS (USER_ID),
    CONSTRAINT FK_BENEF_DELETED_BY   FOREIGN KEY (DELETED_BY_USER_ID) REFERENCES APP_USERS (USER_ID),
    CONSTRAINT CK_BENEF_STATUS       CHECK (STATUS IN ('ACTIVE', 'DELETED')),
    -- The API trims the nickname; the database refuses blank, padded or control-character text.
    CONSTRAINT CK_BENEF_NICKNAME     CHECK (
        NICKNAME = TRIM(NICKNAME)
        AND NOT REGEXP_LIKE(NICKNAME, '[[:cntrl:]]')
    ),
    -- Both deletion columns are set exactly when the row is DELETED.
    CONSTRAINT CK_BENEF_DELETED      CHECK (
        (STATUS = 'ACTIVE'  AND DELETED_AT IS NULL     AND DELETED_BY_USER_ID IS NULL)
        OR (STATUS = 'DELETED' AND DELETED_AT IS NOT NULL AND DELETED_BY_USER_ID IS NOT NULL)
    )
);

-- At most one ACTIVE beneficiary per (customer, account) (B3). A DELETED row yields an
-- all-NULL key, which Oracle leaves out of the index, so the account can be re-added.
CREATE UNIQUE INDEX UX_BENEF_ACTIVE ON BENEFICIARIES (
    CASE WHEN STATUS = 'ACTIVE' THEN CUSTOMER_ID END,
    CASE WHEN STATUS = 'ACTIVE' THEN ACCOUNT_ID END
);

-- Serves the list (customer's rows) and the B5 rolling 24 h add count (CREATED_AT range).
CREATE INDEX IX_BENEF_CUSTOMER ON BENEFICIARIES (CUSTOMER_ID, CREATED_AT);

-- Once DELETED a row is frozen, and identity columns never change. Only the
-- ACTIVE -> DELETED transition (setting STATUS and the two deletion columns) is
-- allowed, and no one may DELETE a row, including the owner. Same pattern and error
-- range as TRG_AUDIT_EVENTS_IMMUTABLE (-20001) and TRG_IDEMP_COMPLETED_IMMUTABLE (-20002).
CREATE TRIGGER TRG_BENEF_IMMUTABLE
BEFORE UPDATE OR DELETE ON BENEFICIARIES
FOR EACH ROW
BEGIN
    IF DELETING THEN
        RAISE_APPLICATION_ERROR(-20003, 'BENEFICIARIES rows cannot be deleted; set STATUS = DELETED');
    END IF;
    IF :OLD.STATUS = 'DELETED'
       OR :NEW.BENEFICIARY_ID     <> :OLD.BENEFICIARY_ID
       OR :NEW.CUSTOMER_ID        <> :OLD.CUSTOMER_ID
       OR :NEW.ACCOUNT_ID         <> :OLD.ACCOUNT_ID
       OR :NEW.NICKNAME           <> :OLD.NICKNAME
       OR :NEW.CREATED_BY_USER_ID <> :OLD.CREATED_BY_USER_ID
       OR :NEW.CREATED_AT         <> :OLD.CREATED_AT THEN
        RAISE_APPLICATION_ERROR(-20003, 'BENEFICIARIES: only ACTIVE -> DELETED is allowed');
    END IF;
END;
/
