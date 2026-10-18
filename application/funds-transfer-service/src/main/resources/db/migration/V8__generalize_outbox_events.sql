-- V8: outbox carries events for more than transfers (Release 2)
-- Design: docs/release-2-event-streaming.md (decision E1). Additive: existing rows and columns keep their
-- meaning, TRANSFER_ID stays (with its FK) for transfer events.
-- Oracle DDL auto-commits: if this script fails part-way, repair forward, do not edit it.

-- 1. Which aggregate an event belongs to. Existing rows are all transfer events. Adding a NOT NULL column
--    with a DEFAULT is a dictionary-only change in Oracle, so it does not rewrite the table.
ALTER TABLE OUTBOX_EVENTS ADD (
    AGGREGATE_TYPE VARCHAR2(30) DEFAULT 'TRANSFER' NOT NULL,
    AGGREGATE_ID   RAW(16)
);

UPDATE OUTBOX_EVENTS SET AGGREGATE_ID = TRANSFER_ID WHERE AGGREGATE_ID IS NULL;
COMMIT;

ALTER TABLE OUTBOX_EVENTS MODIFY (AGGREGATE_ID NOT NULL);

-- 2. Non-transfer events have no transfer.
ALTER TABLE OUTBOX_EVENTS MODIFY (TRANSFER_ID NULL);

-- 3. TRANSFER_ID is set exactly for transfer events, and then equals the aggregate id, so FK_OUTBOX_TRANSFER
--    still guarantees every transfer event points at a real transfer.
ALTER TABLE OUTBOX_EVENTS ADD (
    CONSTRAINT CK_OUTBOX_AGG_TYPE CHECK (AGGREGATE_TYPE IN ('TRANSFER', 'BENEFICIARY')),
    CONSTRAINT CK_OUTBOX_AGG_TRANSFER CHECK (
        (AGGREGATE_TYPE = 'TRANSFER' AND TRANSFER_ID IS NOT NULL AND TRANSFER_ID = AGGREGATE_ID)
        OR (AGGREGATE_TYPE <> 'TRANSFER' AND TRANSFER_ID IS NULL)
    )
);
