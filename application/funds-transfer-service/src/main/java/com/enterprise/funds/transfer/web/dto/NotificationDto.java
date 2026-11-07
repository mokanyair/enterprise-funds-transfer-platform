package com.enterprise.funds.transfer.web.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

/** One notification. {@code readAt} is always present and null while unread (contract: nullable, required). */
public record NotificationDto(String notificationId, String category, String type, String entityType, String entityId,
                              ObjectNode params, Instant occurredAt, Instant createdAt, Instant readAt) {}
