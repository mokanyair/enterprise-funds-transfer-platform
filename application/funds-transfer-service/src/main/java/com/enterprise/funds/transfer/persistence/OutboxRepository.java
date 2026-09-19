package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.persistence.Rows.OutboxRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Written in the same transaction as the posting, so an event exists if and only if the posting committed. */
    public void insert(UUID eventId, UUID transferId, String eventType, String payloadJson, OffsetDateTime now) {
        var p = JdbcSupport.params();
        JdbcSupport.id(p, "id", eventId);
        JdbcSupport.id(p, "transfer", transferId);
        p.addValue("type", eventType).addValue("payload", payloadJson).addValue("now", now);
        jdbc.update("""
                INSERT INTO OUTBOX_EVENTS (EVENT_ID, TRANSFER_ID, EVENT_TYPE, PAYLOAD, STATUS, ATTEMPTS, CREATED_AT)
                VALUES (:id, :transfer, :type, :payload, 'PENDING', 0, :now)
                """, p);
    }

    /** Oldest pending events. Plain read (uses IX_OUTBOX_STATUS); each event is then claimed individually. */
    public List<UUID> findPendingIds(int limit) {
        return jdbc.query("""
                SELECT EVENT_ID FROM OUTBOX_EVENTS WHERE STATUS = 'PENDING'
                 ORDER BY CREATED_AT FETCH FIRST :n ROWS ONLY
                """, JdbcSupport.params().addValue("n", limit), (rs, i) -> JdbcSupport.uuid(rs, "EVENT_ID"));
    }

    /** Claims one pending event; SKIP LOCKED lets several publisher instances run without duplicating work. */
    public Optional<OutboxRow> claim(UUID eventId) {
        List<OutboxRow> rows = jdbc.query("""
                SELECT EVENT_ID, TRANSFER_ID, EVENT_TYPE, PAYLOAD FROM OUTBOX_EVENTS
                 WHERE EVENT_ID = :id AND STATUS = 'PENDING' FOR UPDATE SKIP LOCKED
                """, JdbcSupport.id(JdbcSupport.params(), "id", eventId), (rs, i) -> new OutboxRow(
                JdbcSupport.uuid(rs, "EVENT_ID"), JdbcSupport.uuid(rs, "TRANSFER_ID"),
                rs.getString("EVENT_TYPE"), rs.getString("PAYLOAD")));
        return rows.stream().findFirst();
    }

    public void markPublished(UUID eventId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE OUTBOX_EVENTS SET STATUS = 'PUBLISHED', PUBLISHED_AT = :now, LAST_ATTEMPT_AT = :now,
                       ATTEMPTS = ATTEMPTS + 1 WHERE EVENT_ID = :id
                """, JdbcSupport.id(JdbcSupport.params(), "id", eventId).addValue("now", now));
    }

    /** Counts the failed attempt; becomes FAILED (needs a human) after maxAttempts. */
    public void recordFailure(UUID eventId, OffsetDateTime now, int maxAttempts) {
        jdbc.update("""
                UPDATE OUTBOX_EVENTS
                   SET ATTEMPTS = ATTEMPTS + 1, LAST_ATTEMPT_AT = :now,
                       STATUS = CASE WHEN ATTEMPTS + 1 >= :max THEN 'FAILED' ELSE 'PENDING' END
                 WHERE EVENT_ID = :id
                """, JdbcSupport.id(JdbcSupport.params(), "id", eventId).addValue("now", now).addValue("max", maxAttempts));
    }
}
