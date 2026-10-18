package com.enterprise.funds.transfer.notifications;

import com.enterprise.funds.transfer.persistence.Ids;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** NOTIFICATIONS (V9). Insert-only for the consumer; READ_AT is for the future Notifications API. */
@Repository
public class NotificationRepository {

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

    private static MapSqlParameterSource id(MapSqlParameterSource p, String name, UUID value) {
        return p.addValue(name, value == null ? null : Ids.toBytes(value), Types.BINARY);
    }
}
