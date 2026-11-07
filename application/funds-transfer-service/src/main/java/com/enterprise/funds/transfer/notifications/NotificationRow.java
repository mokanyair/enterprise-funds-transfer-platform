package com.enterprise.funds.transfer.notifications;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One stored notification as the read side sees it. {@code params} is the stored JSON text. */
public record NotificationRow(
        UUID id, String category, String type, String entityType, UUID entityId, String params,
        OffsetDateTime occurredAt, OffsetDateTime createdAt, OffsetDateTime readAt) {}
