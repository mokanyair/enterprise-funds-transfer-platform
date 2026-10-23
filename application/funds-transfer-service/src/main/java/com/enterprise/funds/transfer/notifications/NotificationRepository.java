package com.enterprise.funds.transfer.notifications;

import com.enterprise.funds.transfer.persistence.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * NOTIFICATIONS (V9, V11). The consumer only inserts; the Notifications API reads and sets READ_AT. Every API read
 * and update is scoped by CUSTOMER_ID, so another customer's row is never loaded or changed. TRG_NOTIF_IMMUTABLE
 * allows READ_AT to be set once, so every update here is guarded by READ_AT IS NULL.
 */
@Repository
public class NotificationRepository {

    private static final RowMapper<NotificationRow> ROW = (rs, i) -> new NotificationRow(
            uuid(rs, "NOTIFICATION_ID"), rs.getString("CATEGORY"), rs.getString("TYPE"), rs.getString("ENTITY_TYPE"),
            uuid(rs, "ENTITY_ID"), rs.getString("PARAMS"), rs.getObject("OCCURRED_AT", OffsetDateTime.class),
            rs.getObject("CREATED_AT", OffsetDateTime.class), rs.getObject("READ_AT", OffsetDateTime.class));

    /** Must match the V11 index expression exactly, or the index is not used. */
    private static final String UNREAD_OF_CUSTOMER = "(CASE WHEN READ_AT IS NULL THEN CUSTOMER_ID END) = :c";

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Served by UK_NOTIF_EVENT_CUSTOMER. */
    public boolean exists(UUID sourceEventId, UUID customerId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM NOTIFICATIONS WHERE SOURCE_EVENT_ID = :e AND CUSTOMER_ID = :c",
                id(id(new MapSqlParameterSource(), "e", sourceEventId), "c", customerId), Integer.class);
        return n != null && n > 0;
    }

    /** Throws DuplicateKeyException if this (event, customer) was stored concurrently; the retry then skips it. */
    public void insert(NotificationDraft d, String paramsJson, OffsetDateTime now) {
        var p = id(new MapSqlParameterSource(), "id", Ids.newId());
        id(p, "c", d.customerId());
        id(p, "e", d.sourceEventId());
        id(p, "entity", d.entityId());
        p.addValue("cat", d.category()).addValue("type", d.type()).addValue("etype", d.entityType())
                .addValue("params", paramsJson).addValue("occurred", d.occurredAt()).addValue("now", now);
        jdbc.update("""
                INSERT INTO NOTIFICATIONS (NOTIFICATION_ID, CUSTOMER_ID, CATEGORY, TYPE, SOURCE_EVENT_ID, ENTITY_TYPE,
                                           ENTITY_ID, PARAMS, OCCURRED_AT, CREATED_AT)
                VALUES (:id, :c, :cat, :type, :e, :etype, :entity, :params, :occurred, :now)
                """, p);
    }

    /** Newest first (N3), served by IX_NOTIF_CUSTOMER. A null category means all categories. */
    public List<NotificationRow> list(UUID customerId, String category, boolean unreadOnly, long offset, int size) {
        return jdbc.query("SELECT NOTIFICATION_ID, CATEGORY, TYPE, ENTITY_TYPE, ENTITY_ID, PARAMS, OCCURRED_AT, "
                        + "CREATED_AT, READ_AT FROM NOTIFICATIONS" + filter(category, unreadOnly)
                        + " ORDER BY CREATED_AT DESC, NOTIFICATION_ID DESC OFFSET :off ROWS FETCH NEXT :size ROWS ONLY",
                filterParams(customerId, category).addValue("off", offset).addValue("size", size), ROW);
    }

    public long count(UUID customerId, String category, boolean unreadOnly) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM NOTIFICATIONS" + filter(category, unreadOnly),
                filterParams(customerId, category), Long.class);
        return n == null ? 0 : n;
    }

    /** An index-only scan of IX_NOTIF_UNREAD (V11), which holds only unread rows. */
    public long countUnread(UUID customerId) {
        return count(customerId, null, true);
    }

    public boolean existsFor(UUID customerId, UUID notificationId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM NOTIFICATIONS WHERE NOTIFICATION_ID = :id AND CUSTOMER_ID = :c",
                id(id(new MapSqlParameterSource(), "id", notificationId), "c", customerId), Integer.class);
        return n != null && n > 0;
    }

    /** Returns false when the row is unknown, another customer's, or already read (the first READ_AT is kept). */
    public boolean markRead(UUID customerId, UUID notificationId, OffsetDateTime now) {
        var p = id(id(new MapSqlParameterSource(), "id", notificationId), "c", customerId).addValue("now", now);
        return jdbc.update("""
                UPDATE NOTIFICATIONS SET READ_AT = :now
                 WHERE NOTIFICATION_ID = :id AND CUSTOMER_ID = :c AND READ_AT IS NULL
                """, p) == 1;
    }

    /** Only rows stored by {@code now}: one that lands during the request stays unread (N5). */
    public int markAllRead(UUID customerId, OffsetDateTime now) {
        return jdbc.update("UPDATE NOTIFICATIONS SET READ_AT = :now WHERE " + UNREAD_OF_CUSTOMER
                + " AND CREATED_AT <= :now", id(new MapSqlParameterSource(), "c", customerId).addValue("now", now));
    }

    /** Unread filter by the IX_NOTIF_UNREAD expression; it means CUSTOMER_ID = :c AND READ_AT IS NULL. */
    private static String filter(String category, boolean unreadOnly) {
        return " WHERE " + (unreadOnly ? UNREAD_OF_CUSTOMER : "CUSTOMER_ID = :c")
                + (category == null ? "" : " AND CATEGORY = :cat");
    }

    private static MapSqlParameterSource filterParams(UUID customerId, String category) {
        return id(new MapSqlParameterSource(), "c", customerId).addValue("cat", category);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return Ids.fromBytes(rs.getBytes(column));
    }

    private static MapSqlParameterSource id(MapSqlParameterSource p, String name, UUID value) {
        return p.addValue(name, value == null ? null : Ids.toBytes(value), Types.BINARY);
    }
}
