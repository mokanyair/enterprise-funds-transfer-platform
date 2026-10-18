package com.enterprise.funds.transfer.notifications;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One notification to store: the recipient, what happened, and the parameters the read side renders text from. */
public record NotificationDraft(
        UUID customerId, String category, String type, UUID sourceEventId, String entityType, UUID entityId,
        ObjectNode params, OffsetDateTime occurredAt) {}
