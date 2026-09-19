package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.persistence.OutboxRepository;
import com.enterprise.funds.transfer.web.dto.TransferDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Writes the event in the caller's transaction (outbox pattern). Kafka delivery is a separate, at-least-once step. */
@Service
public class OutboxWriter {

    private final OutboxRepository repo;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repo, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void write(String eventType, UUID transferId, TransferDto transfer) {
        UUID eventId = Ids.newId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        ObjectNode payload = mapper.valueToTree(transfer);
        payload.put("eventId", eventId.toString()); // consumers deduplicate on this
        payload.put("eventType", eventType);
        payload.put("occurredAt", now.toInstant().toString());
        try {
            repo.insert(eventId, transferId, eventType, mapper.writeValueAsString(payload), now);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise outbox event", e);
        }
    }
}
