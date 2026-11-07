package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.notifications.NotificationCategory;
import com.enterprise.funds.transfer.notifications.NotificationRepository;
import com.enterprise.funds.transfer.notifications.NotificationRow;
import com.enterprise.funds.transfer.web.dto.NotificationDto;
import com.enterprise.funds.transfer.web.dto.NotificationPageDto;
import com.enterprise.funds.transfer.web.dto.UnreadCountDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The customer's notification inbox (docs/release-2-notifications-contract.md, decisions N1-N7). Notifications
 * belong to the customer (N1); operators get 403 everywhere. Reading and marking read are not audited (N7).
 */
@Service
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    /** NotificationParams in the contract. Anything else in a stored row is dropped, never sent (N2). */
    static final Set<String> PARAM_KEYS = Set.of(
            "transferId", "amount", "currency", "counterpartyLast4", "beneficiaryId", "accountIdLast4");

    private final Clock clock;
    private final NotificationRepository notifications;
    private final ObjectMapper mapper;

    public NotificationService(Clock clock, NotificationRepository notifications, ObjectMapper mapper) {
        this.clock = clock;
        this.notifications = notifications;
        this.mapper = mapper;
    }

    public NotificationPageDto list(Actor actor, int page, int size, NotificationCategory category, boolean unreadOnly) {
        requireCustomer(actor);
        String cat = category == null ? null : category.name();
        var items = notifications.list(actor.customerId(), cat, unreadOnly, (long) page * size, size).stream()
                .map(this::toDto).toList();
        long total = notifications.count(actor.customerId(), cat, unreadOnly);
        return new NotificationPageDto(items, page, size, total, (int) ((total + size - 1) / size),
                notifications.countUnread(actor.customerId()));
    }

    public UnreadCountDto unreadCount(Actor actor) {
        requireCustomer(actor);
        return new UnreadCountDto(notifications.countUnread(actor.customerId()));
    }

    /** Idempotent (N5): already read is 204 again. Unknown and another customer's are the same 404. */
    public void markRead(Actor actor, UUID notificationId) {
        requireCustomer(actor);
        if (!notifications.markRead(actor.customerId(), notificationId, OffsetDateTime.now(clock))
                && !notifications.existsFor(actor.customerId(), notificationId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    public void markAllRead(Actor actor) {
        requireCustomer(actor);
        notifications.markAllRead(actor.customerId(), OffsetDateTime.now(clock));
    }

    NotificationDto toDto(NotificationRow r) {
        return new NotificationDto(r.id().toString(), r.category(), r.type(), r.entityType(), r.entityId().toString(),
                params(r), instant(r.occurredAt()), instant(r.createdAt()), instant(r.readAt()));
    }

    /** Allow-listed keys, string values only. An unreadable row still lists, with empty params, rather than 500. */
    private ObjectNode params(NotificationRow r) {
        ObjectNode out = mapper.createObjectNode();
        try {
            JsonNode stored = mapper.readTree(r.params());
            for (String key : PARAM_KEYS) {
                JsonNode v = stored == null ? null : stored.get(key);
                if (v != null && v.isTextual()) {
                    out.put(key, v.asText());
                }
            }
        } catch (JsonProcessingException e) {
            LOG.warn("Notification {} has unreadable params; sent without them", r.id());
        }
        return out;
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static void requireCustomer(Actor actor) {
        if (actor.isOperator()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
}
