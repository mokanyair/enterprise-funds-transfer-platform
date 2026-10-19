-- V11: index for the unread-notification badge (Release 2)
-- Tables: NOTIFICATIONS
-- Design: docs/release-2-notifications-contract.md (decision N4). Additive only.
-- Oracle DDL auto-commits: if this script fails part-way, repair forward, do not edit it.

-- GET /notifications/unread-count is polled by every signed-in session, so it must not read the customer's whole
-- history. Only unread rows have a non-NULL key, and Oracle does not index all-NULL keys, so this index holds just
-- the unread rows and shrinks as they are read. Queries must use the same expression to use it:
--   WHERE (CASE WHEN READ_AT IS NULL THEN CUSTOMER_ID END) = :customerId
-- A plain (CUSTOMER_ID, READ_AT) index does not work here: Oracle indexes a TIMESTAMP WITH TIME ZONE column as
-- SYS_EXTRACT_UTC(READ_AT), which "READ_AT IS NULL" cannot use, so the table is read anyway.
CREATE INDEX IX_NOTIF_UNREAD ON NOTIFICATIONS (CASE WHEN READ_AT IS NULL THEN CUSTOMER_ID END);
