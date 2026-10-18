package com.enterprise.funds.transfer.notifications;

import static com.enterprise.funds.transfer.events.EventEnvelope.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Pure mapping from one domain event to the notifications it causes (decision E7). No I/O except the optional
 * party resolver, which is used only for v0 transfer events written before parties existed.
 *
 * <pre>
 * TransferCompleted      SOURCE -> TRANSFER_SENT, DESTINATION (other customer) -> TRANSFER_RECEIVED
 * TransferPendingReview  SOURCE -> TRANSFER_UNDER_REVIEW
 * TransferRejected       SOURCE -> TRANSFER_REJECTED
 * TransferCancelled      SOURCE -> TRANSFER_CANCELLED
 * BeneficiaryAdded       OWNER  -> BENEFICIARY_ADDED   (SECURITY)
 * BeneficiaryDeleted     OWNER  -> BENEFICIARY_DELETED (SECURITY)
 * anything else          nothing (forward compatible)
 * </pre>
 *
 * Params never carry free text (reference, nickname) or full account numbers: only last four characters.
 */
public final class NotificationProjector {

    /** Customer ids by role, for an event that has no parties. */
    public interface PartyResolver extends Function<JsonNode, Map<String, UUID>> {}

    private final ObjectMapper mapper;

    public NotificationProjector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<NotificationDraft> project(JsonNode event, PartyResolver legacyParties) {
        UUID eventId = uuid(event, EVENT_ID);
        String eventType = text(event, EVENT_TYPE);
        OffsetDateTime occurredAt = time(event);
        Map<String, UUID> parties = parties(event, legacyParties);
        List<NotificationDraft> out = new ArrayList<>();

        switch (eventType) {
            case TRANSFER_COMPLETED -> {
                UUID source = parties.get(ROLE_SOURCE);
                UUID destination = parties.get(ROLE_DESTINATION);
                add(out, source, "TRANSACTION", "TRANSFER_SENT", eventId, event, occurredAt, "destinationAccountId");
                if (destination != null && !destination.equals(source)) {
                    add(out, destination, "TRANSACTION", "TRANSFER_RECEIVED", eventId, event, occurredAt, "sourceAccountId");
                }
            }
            case TRANSFER_PENDING_REVIEW ->
                    add(out, parties.get(ROLE_SOURCE), "TRANSACTION", "TRANSFER_UNDER_REVIEW", eventId, event, occurredAt, "destinationAccountId");
            case TRANSFER_REJECTED ->
                    add(out, parties.get(ROLE_SOURCE), "TRANSACTION", "TRANSFER_REJECTED", eventId, event, occurredAt, "destinationAccountId");
            case TRANSFER_CANCELLED ->
                    add(out, parties.get(ROLE_SOURCE), "TRANSACTION", "TRANSFER_CANCELLED", eventId, event, occurredAt, "destinationAccountId");
            case BENEFICIARY_ADDED -> addBeneficiary(out, parties.get(ROLE_OWNER), "BENEFICIARY_ADDED", eventId, event, occurredAt);
            case BENEFICIARY_DELETED -> addBeneficiary(out, parties.get(ROLE_OWNER), "BENEFICIARY_DELETED", eventId, event, occurredAt);
            default -> { /* unknown event type: nothing to notify */ }
        }
        return out;
    }

    private void add(List<NotificationDraft> out, UUID customer, String category, String type, UUID eventId,
                     JsonNode event, OffsetDateTime occurredAt, String counterpartyField) {
        if (customer == null) {
            throw new MalformedEventException("Event " + eventId + " has no recipient for " + type);
        }
        UUID transferId = uuid(event, "transferId");
        ObjectNode params = mapper.createObjectNode();
        params.put("transferId", transferId.toString());
        params.put("amount", text(event, "amount"));
        params.put("currency", text(event, "currency"));
        params.put("counterpartyLast4", last4(text(event, counterpartyField)));
        out.add(new NotificationDraft(customer, category, type, eventId, TRANSFER, transferId, params, occurredAt));
    }

    private void addBeneficiary(List<NotificationDraft> out, UUID customer, String type, UUID eventId, JsonNode event,
                                OffsetDateTime occurredAt) {
        if (customer == null) {
            throw new MalformedEventException("Event " + eventId + " has no owner");
        }
        UUID beneficiaryId = uuid(event, "beneficiaryId");
        ObjectNode params = mapper.createObjectNode();
        params.put("beneficiaryId", beneficiaryId.toString());
        params.put("accountIdLast4", last4(text(event, "accountIdLast4")));
        out.add(new NotificationDraft(customer, "SECURITY", type, eventId, BENEFICIARY, beneficiaryId, params, occurredAt));
    }

    private static Map<String, UUID> parties(JsonNode event, PartyResolver legacy) {
        JsonNode list = event.get(PARTIES);
        Map<String, UUID> byRole = new LinkedHashMap<>();
        if (list == null || !list.isArray()) {
            // v0 transfer event (before E2): only account numbers, so look the owners up.
            return legacy == null ? byRole : legacy.apply(event);
        }
        for (JsonNode p : list) {
            byRole.put(text(p, "role"), uuid(p, "customerId"));
        }
        return byRole;
    }

    private static String last4(String value) {
        return value.substring(Math.max(0, value.length() - 4));
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isEmpty()) {
            throw new MalformedEventException("Missing field " + field);
        }
        return v.asText();
    }

    static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("Field " + field + " is not a UUID", e);
        }
    }

    private static OffsetDateTime time(JsonNode node) {
        try {
            return OffsetDateTime.parse(text(node, OCCURRED_AT));
        } catch (DateTimeParseException e) {
            throw new MalformedEventException("Field occurredAt is not a timestamp", e);
        }
    }
}
