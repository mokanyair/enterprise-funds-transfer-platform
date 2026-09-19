package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.persistence.AuditRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repo;
    private final Clock clock;

    public AuditService(AuditRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** Participates in the caller's transaction when there is one, so the audit row commits with the action. */
    public void record(Actor actor, String action, String entityType, UUID entityId, String outcome,
                       String correlationId, String detail) {
        repo.insert(actor == null ? null : actor.userId(), actor == null ? "SYSTEM" : actor.role().name(),
                action, entityType, entityId, outcome, correlationId, detail, OffsetDateTime.now(clock));
    }

    /** For failure paths after a rollback: an audit problem must not hide the original error. */
    public void recordQuietly(Actor actor, String action, String entityType, UUID entityId, String outcome,
                              String correlationId, String detail) {
        try {
            record(actor, action, entityType, entityId, outcome, correlationId, detail);
        } catch (RuntimeException e) {
            LOG.warn("Could not write audit event {} (correlationId={})", action, correlationId, e);
        }
    }
}
