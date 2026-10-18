package com.enterprise.funds.transfer.notifications;

import static com.enterprise.funds.transfer.events.EventEnvelope.ROLE_DESTINATION;
import static com.enterprise.funds.transfer.events.EventEnvelope.ROLE_SOURCE;

import com.enterprise.funds.transfer.persistence.AccountRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns one Kafka record into stored notifications, idempotently (E6). All of an event's rows are written in one
 * transaction; rows that already exist are skipped, so a redelivered event changes nothing. Kafka's offset is
 * committed only after this returns (ack-mode record), so a crash between commit and ack is a harmless redelivery.
 */
@Service
public class NotificationEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationEventHandler.class);

    private final ObjectMapper mapper;
    private final NotificationProjector projector;
    private final NotificationRepository notifications;
    private final AccountRepository accounts;
    private final TransactionTemplate tx;
    private final Clock clock;

    public NotificationEventHandler(ObjectMapper mapper, NotificationRepository notifications, AccountRepository accounts,
                                    TransactionTemplate postingTransaction, Clock clock) {
        this.mapper = mapper;
        this.projector = new NotificationProjector(mapper);
        this.notifications = notifications;
        this.accounts = accounts;
        this.tx = postingTransaction;
        this.clock = clock;
    }

    /** Returns how many notifications were newly stored (0 for a duplicate or an event that notifies no one). */
    public int handle(String payload) {
        JsonNode event;
        try {
            event = mapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("Not JSON", e);
        }
        if (event == null || !event.isObject()) {
            throw new MalformedEventException("Not a JSON object");
        }
        List<NotificationDraft> drafts = projector.project(event, this::legacyParties);
        Integer stored = tx.execute(status -> {
            int n = 0;
            OffsetDateTime now = OffsetDateTime.now(clock);
            for (NotificationDraft d : drafts) {
                if (notifications.exists(d.sourceEventId(), d.customerId())) {
                    continue;
                }
                notifications.insert(d, d.params().toString(), now);
                n++;
            }
            return n;
        });
        if (!drafts.isEmpty() && stored != null && stored == 0) {
            LOG.debug("Event {} already projected; skipped", drafts.get(0).sourceEventId());
        }
        return stored == null ? 0 : stored;
    }

    /** v0 transfer events carry account numbers only. */
    private Map<String, UUID> legacyParties(JsonNode event) {
        Map<String, UUID> byRole = new LinkedHashMap<>();
        accounts.findByNumber(NotificationProjector.text(event, "sourceAccountId"))
                .ifPresent(a -> byRole.put(ROLE_SOURCE, a.customerId()));
        accounts.findByNumber(NotificationProjector.text(event, "destinationAccountId"))
                .ifPresent(a -> byRole.put(ROLE_DESTINATION, a.customerId()));
        return byRole;
    }
}
