-- V9: notifications projected from domain events (Release 2)
-- Tables: NOTIFICATIONS
-- Design: docs/release-2-event-streaming.md (decisions E5-E9). Written only by the Kafka consumer; read by the
-- future Notifications API. Additive only.
-- Oracle DDL auto-commits: if this script fails part-way, repair forward, do not edit it.

-- One row per (event, recipient customer). The text is rendered at read time from TYPE and PARAMS, so wording
-- can change and be localised without rewriting stored rows.
CREATE TABLE NOTIFICATIONS (
    NOTIFICATION_ID  RAW(16)                  NOT NULL,
    CUSTOMER_ID      RAW(16)                  NOT NULL,
    CATEGORY         VARCHAR2(12)             NOT NULL,
    TYPE             VARCHAR2(40)             NOT NULL,
    SOURCE_EVENT_ID  RAW(16)                  NOT NULL,
    ENTITY_TYPE      VARCHAR2(30)             NOT NULL,
    ENTITY_ID        RAW(16)                  NOT NULL,
    PARAMS           CLOB                     NOT NULL,
    OCCURRED_AT      TIMESTAMP WITH TIME ZONE NOT NULL,
    CREATED_AT       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    READ_AT          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT PK_NOTIFICATIONS       PRIMARY KEY (NOTIFICATION_ID),
    CONSTRAINT FK_NOTIF_CUSTOMER      FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMERS (CUSTOMER_ID),
    -- The consumer's idempotency key (E6): a redelivered event cannot notify the same customer twice.
    CONSTRAINT UK_NOTIF_EVENT_CUSTOMER UNIQUE (SOURCE_EVENT_ID, CUSTOMER_ID),
    CONSTRAINT CK_NOTIF_CATEGORY      CHECK (CATEGORY IN ('TRANSACTION', 'SECURITY', 'ACCOUNT')),
    CONSTRAINT CK_NOTIF_TYPE          CHECK (TYPE IN (
        'TRANSFER_SENT', 'TRANSFER_RECEIVED', 'TRANSFER_UNDER_REVIEW', 'TRANSFER_REJECTED', 'TRANSFER_CANCELLED',
        'BENEFICIARY_ADDED', 'BENEFICIARY_DELETED')),
    CONSTRAINT CK_NOTIF_ENTITY_TYPE   CHECK (ENTITY_TYPE IN ('TRANSFER', 'BENEFICIARY')),
    CONSTRAINT CK_NOTIF_PARAMS        CHECK (PARAMS IS JSON)
);

-- The future list endpoint: a customer's notifications, newest first. UK_NOTIF_EVENT_CUSTOMER serves the
-- consumer's duplicate check, so no separate SOURCE_EVENT_ID index.
CREATE INDEX IX_NOTIF_CUSTOMER ON NOTIFICATIONS (CUSTOMER_ID, CREATED_AT);

-- A notification is a fact: only READ_AT may change, once, from NULL to a time. Deletion is left to the
-- retention purge (owner identity); the runtime user has no DELETE grant (P8).
CREATE TRIGGER TRG_NOTIF_IMMUTABLE
BEFORE UPDATE ON NOTIFICATIONS
FOR EACH ROW
BEGIN
    IF :OLD.READ_AT IS NOT NULL
       OR :NEW.NOTIFICATION_ID <> :OLD.NOTIFICATION_ID
       OR :NEW.CUSTOMER_ID     <> :OLD.CUSTOMER_ID
       OR :NEW.CATEGORY        <> :OLD.CATEGORY
       OR :NEW.TYPE            <> :OLD.TYPE
       OR :NEW.SOURCE_EVENT_ID <> :OLD.SOURCE_EVENT_ID
       OR :NEW.ENTITY_TYPE     <> :OLD.ENTITY_TYPE
       OR :NEW.ENTITY_ID       <> :OLD.ENTITY_ID
       OR DBMS_LOB.COMPARE(:NEW.PARAMS, :OLD.PARAMS) <> 0
       OR :NEW.OCCURRED_AT     <> :OLD.OCCURRED_AT
       OR :NEW.CREATED_AT      <> :OLD.CREATED_AT THEN
        RAISE_APPLICATION_ERROR(-20004, 'NOTIFICATIONS: only READ_AT may be set, once');
    END IF;
END;
/
