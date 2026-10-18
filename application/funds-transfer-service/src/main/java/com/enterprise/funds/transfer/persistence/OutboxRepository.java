package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.persistence.Rows.RawOutboxRow;
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

    /**
     * Written in the same transaction as the change it describes, so an event exists if and only if that change
     * committed. TRANSFER_ID is filled only for transfer events (CK_OUTBOX_AGG_TRANSFER), keeping the V5 FK.
     */
    public void insert(UUID eventId, String aggregateType, UUID aggregateId, String eventType, String payloadJson,
                       OffsetDateTime now) {
        var p = JdbcSupport.params();
        JdbcSupport.id(p, "id", eventId);
        JdbcSupport.id(p, "agg", aggregateId);
        JdbcSupport.id(p, "transfer", "TRANSFER".equals(aggregateType) ? aggregateId : null);
        p.addValue("aggType", aggregateType).addValue("type", eventType).addValue("payload", payloadJson)
                .addValue("now", now);
        jdbc.update("""
                INSERT INTO OUTBOX_EVENTS (EVENT_ID, AGGREGATE_TYPE, AGGREGATE_ID, TRANSFER_ID, EVENT_TYPE, PAYLOAD,
                                           STATUS, ATTEMPTS, CREATED_AT)
                VALUES (:id, :aggType, :agg, :transfer, :type, :payload, 'PENDING', 0, :now)
                """, p);
    }

    /**
     * Oldest pending events, as raw keys. Plain read (uses IX_OUTBOX_STATUS); each event is then claimed individually.
     * Nothing is decoded here, so a malformed row cannot fail the scan for the rows behind it.
     */
    public List<byte[]> findPendingKeys(int limit) {
        return jdbc.query("""
                SELECT EVENT_ID FROM OUTBOX_EVENTS WHERE STATUS = 'PENDING'
                 ORDER BY CREATED_AT FETCH FIRST :n ROWS ONLY
                """, JdbcSupport.params().addValue("n", limit), (rs, i) -> rs.getBytes("EVENT_ID"));
    }

    /** Claims one pending event; SKIP LOCKED lets several publisher instances run without duplicating work. */
    public Optional<RawOutboxRow> claim(byte[] key) {
        List<RawOutboxRow> rows = jdbc.query("""
                SELECT EVENT_ID, AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD FROM OUTBOX_EVENTS
                 WHERE EVENT_ID = :id AND STATUS = 'PENDING' FOR UPDATE SKIP LOCKED
                """, raw(key), (rs, i) -> new RawOutboxRow(rs.getBytes("EVENT_ID"), rs.getString("AGGREGATE_TYPE"),
                rs.getBytes("AGGREGATE_ID"), rs.getString("EVENT_TYPE"), rs.getString("PAYLOAD")));
        return rows.stream().findFirst();
    }

    public void markPublished(byte[] key, OffsetDateTime now) {
        jdbc.update("""
                UPDATE OUTBOX_EVENTS SET STATUS = 'PUBLISHED', PUBLISHED_AT = :now, LAST_ATTEMPT_AT = :now,
                       ATTEMPTS = ATTEMPTS + 1 WHERE EVENT_ID = :id
                """, raw(key).addValue("now", now));
    }

    /** Counts the failed attempt; becomes FAILED (needs a human) after maxAttempts. */
    public void recordFailure(byte[] key, OffsetDateTime now, int maxAttempts) {
        jdbc.update("""
                UPDATE OUTBOX_EVENTS
                   SET ATTEMPTS = ATTEMPTS + 1, LAST_ATTEMPT_AT = :now,
                       STATUS = CASE WHEN ATTEMPTS + 1 >= :max THEN 'FAILED' ELSE 'PENDING' END
                 WHERE EVENT_ID = :id
                """, raw(key).addValue("now", now).addValue("max", maxAttempts));
    }

    /** A row that can never be published (undecodable). FAILED at once, so it is visible and stops being retried. */
    public void quarantine(byte[] key, OffsetDateTime now) {
        jdbc.update("""
                UPDATE OUTBOX_EVENTS SET STATUS = 'FAILED', ATTEMPTS = ATTEMPTS + 1, LAST_ATTEMPT_AT = :now
                 WHERE EVENT_ID = :id AND STATUS = 'PENDING'
                """, raw(key).addValue("now", now));
    }

    /** Rows needing a human (exhausted retries or quarantined). Backs the publisher's health log. */
    public int countFailed() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENTS WHERE STATUS = 'FAILED'",
                JdbcSupport.params(), Integer.class);
        return n == null ? 0 : n;
    }

    private static org.springframework.jdbc.core.namedparam.MapSqlParameterSource raw(byte[] key) {
        return JdbcSupport.params().addValue("id", key, java.sql.Types.BINARY);
    }
}
