-- V4: risk assessments and audit trail
-- Tables: RISK_ASSESSMENTS, AUDIT_EVENTS (+ immutability trigger)

-- Internal only. Never exposed through the API.
CREATE TABLE RISK_ASSESSMENTS (
    ASSESSMENT_ID  RAW(16)                  NOT NULL,
    TRANSFER_ID    RAW(16)                  NOT NULL,
    DECISION       VARCHAR2(10)             NOT NULL,
    REASON         VARCHAR2(500),
    RISK_SCORE     NUMBER(5,2),
    ASSESSED_AT    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT PK_RISK_ASSESSMENTS PRIMARY KEY (ASSESSMENT_ID),
    CONSTRAINT FK_RISK_TRANSFER    FOREIGN KEY (TRANSFER_ID) REFERENCES TRANSFERS (TRANSFER_ID),
    CONSTRAINT CK_RISK_DECISION    CHECK (DECISION IN ('APPROVE', 'REVIEW', 'REJECT'))
);

-- ENTITY_ID intentionally has no FK: it points at several tables, and audit rows
-- must outlive their subject. DETAIL must never contain secrets or payloads.
CREATE TABLE AUDIT_EVENTS (
    AUDIT_ID        RAW(16)                  NOT NULL,
    ACTOR_USER_ID   RAW(16),
    ACTOR_ROLE      VARCHAR2(10)             NOT NULL,
    ACTION          VARCHAR2(50)             NOT NULL,
    ENTITY_TYPE     VARCHAR2(30)             NOT NULL,
    ENTITY_ID       RAW(16)                  NOT NULL,
    OUTCOME         VARCHAR2(10)             NOT NULL,
    CORRELATION_ID  VARCHAR2(64),
    DETAIL          VARCHAR2(2000),
    CREATED_AT      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT PK_AUDIT_EVENTS     PRIMARY KEY (AUDIT_ID),
    CONSTRAINT FK_AUDIT_ACTOR      FOREIGN KEY (ACTOR_USER_ID) REFERENCES APP_USERS (USER_ID),
    CONSTRAINT CK_AUDIT_ACTOR_ROLE CHECK (ACTOR_ROLE IN ('CUSTOMER', 'OPERATOR', 'SYSTEM')),
    CONSTRAINT CK_AUDIT_OUTCOME    CHECK (OUTCOME IN ('SUCCESS', 'DENIED', 'FAILURE'))
);

-- Defence in depth for immutability, on top of the runtime user having only
-- INSERT and SELECT: block UPDATE and DELETE for everyone, including the owner.
-- Does not stop TRUNCATE or DROP by the owner; those are DDL and are covered by
-- privilege separation and the retention runbook.
CREATE TRIGGER TRG_AUDIT_EVENTS_IMMUTABLE
BEFORE UPDATE OR DELETE ON AUDIT_EVENTS
BEGIN
    RAISE_APPLICATION_ERROR(-20001, 'AUDIT_EVENTS is immutable');
END;
/
