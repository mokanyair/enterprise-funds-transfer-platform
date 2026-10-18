package com.enterprise.funds.transfer.service;

import static com.enterprise.funds.transfer.events.EventEnvelope.*;

import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.persistence.OutboxRepository;
import com.enterprise.funds.transfer.persistence.Rows.BeneficiaryRow;
import com.enterprise.funds.transfer.persistence.Rows.TransferView;
import com.enterprise.funds.transfer.web.dto.TransferDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes the event in the caller's transaction (outbox pattern). Kafka delivery is a separate, at-least-once step.
 * Payload shape: {@link com.enterprise.funds.transfer.events.EventEnvelope}.
 */
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

    /** The TransferDto fields stay top-level (v0 shape); parties come from the accounts' owning customers. */
    public void writeTransfer(String eventType, TransferView transfer) {
        ObjectNode payload = mapper.valueToTree(TransferDto.from(transfer));
        ArrayNode parties = payload.putArray(PARTIES);
        party(parties, transfer.sourceCustomerId(), ROLE_SOURCE);
        party(parties, transfer.destinationCustomerId(), ROLE_DESTINATION);
        write(eventType, TRANSFER, transfer.id(), payload);
    }

    /**
     * Carries only the last four characters of the saved account number and never the nickname: the event leaves
     * this service and lives in Kafka for the topic's retention, and both are another customer's or free text.
     */
    public void writeBeneficiary(String eventType, BeneficiaryRow beneficiary) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("beneficiaryId", beneficiary.id().toString());
        String number = beneficiary.accountNumber();
        payload.put("accountIdLast4", number.substring(Math.max(0, number.length() - 4)));
        party(payload.putArray(PARTIES), beneficiary.customerId(), ROLE_OWNER);
        write(eventType, BENEFICIARY, beneficiary.id(), payload);
    }

    private void write(String eventType, String aggregateType, UUID aggregateId, ObjectNode payload) {
        UUID eventId = Ids.newId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        payload.put(EVENT_ID, eventId.toString()); // consumers deduplicate on this
        payload.put(EVENT_TYPE, eventType);
        payload.put(VERSION, SCHEMA_VERSION);
        payload.put(OCCURRED_AT, now.toInstant().toString());
        payload.put(AGGREGATE_TYPE, aggregateType);
        payload.put(AGGREGATE_ID, aggregateId.toString());
        try {
            repo.insert(eventId, aggregateType, aggregateId, eventType, mapper.writeValueAsString(payload), now);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise outbox event", e);
        }
    }

    private static void party(ArrayNode parties, UUID customerId, String role) {
        ObjectNode p = parties.addObject();
        p.put("customerId", customerId.toString());
        p.put("role", role);
    }
}
