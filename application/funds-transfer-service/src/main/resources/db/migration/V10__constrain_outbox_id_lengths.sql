-- V10: outbox ids must be real 16-byte UUIDs (Release 2, Kafka hardening)
-- Design: docs/release-2-event-streaming.md section 8. Additive: two CHECK constraints.
-- RAW(16) accepts 1-16 bytes, but the publisher can only key and route an event with a full UUID. A shorter id
-- once made every publisher poll fail and blocked all events behind it. The publisher now quarantines such rows;
-- these constraints stop them being written at all.
-- Pre-check before applying (must return 0, else repair those rows first; Oracle validates on ADD):
--   SELECT COUNT(*) FROM OUTBOX_EVENTS WHERE UTL_RAW.LENGTH(EVENT_ID) <> 16 OR UTL_RAW.LENGTH(AGGREGATE_ID) <> 16;
-- Oracle DDL auto-commits: if this script fails part-way, repair forward, do not edit it.

ALTER TABLE OUTBOX_EVENTS ADD (
    CONSTRAINT CK_OUTBOX_EVENT_ID_LEN CHECK (LENGTHB(RAWTOHEX(EVENT_ID)) = 32),
    CONSTRAINT CK_OUTBOX_AGG_ID_LEN   CHECK (LENGTHB(RAWTOHEX(AGGREGATE_ID)) = 32)
);
