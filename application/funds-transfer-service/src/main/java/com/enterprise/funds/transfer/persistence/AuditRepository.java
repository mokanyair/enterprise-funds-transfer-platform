package com.enterprise.funds.transfer.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Insert-only. UPDATE and DELETE are blocked by a trigger and by privileges. */
@Repository
public class AuditRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID actorUserId, String actorRole, String action, String entityType, UUID entityId,
                       String outcome, String correlationId, String detail, OffsetDateTime now) {
        var p = JdbcSupport.params();
        JdbcSupport.id(p, "id", Ids.newId());
        JdbcSupport.id(p, "actor", actorUserId);
        JdbcSupport.id(p, "entity", entityId);
        p.addValue("role", actorRole).addValue("action", action).addValue("etype", entityType)
                .addValue("outcome", outcome).addValue("corr", correlationId).addValue("detail", detail)
                .addValue("now", now);
        jdbc.update("""
                INSERT INTO AUDIT_EVENTS (AUDIT_ID, ACTOR_USER_ID, ACTOR_ROLE, ACTION, ENTITY_TYPE, ENTITY_ID,
                                          OUTCOME, CORRELATION_ID, DETAIL, CREATED_AT)
                VALUES (:id, :actor, :role, :action, :etype, :entity, :outcome, :corr, :detail, :now)
                """, p);
    }
}
