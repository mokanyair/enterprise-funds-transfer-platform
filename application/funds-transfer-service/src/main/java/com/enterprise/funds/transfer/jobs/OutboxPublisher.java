package com.enterprise.funds.transfer.jobs;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.persistence.OutboxRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox events at-least-once. Each event is claimed in its own short transaction (SKIP LOCKED, so several
 * instances can run), sent, then marked PUBLISHED. A crash between send and mark means a redelivery, which is why
 * consumers deduplicate on the event id. Kafka availability never affects a transfer: publication is an independent
 * outbox state, not a transfer state.
 */
@Component
@ConditionalOnProperty(prefix = "funds.outbox", name = "publisher-enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final EventSink sink;
    private final TransactionTemplate tx;
    private final FundsProperties props;
    private final Clock clock;

    public OutboxPublisher(OutboxRepository outbox, EventSink sink, TransactionTemplate postingTransaction,
                           FundsProperties props, Clock clock) {
        this.outbox = outbox;
        this.sink = sink;
        this.tx = postingTransaction;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${funds.outbox.poll-interval:1s}")
    public void publishPending() {
        for (UUID id : outbox.findPendingIds(props.outbox().batchSize())) {
            tx.executeWithoutResult(status -> outbox.claim(id).ifPresent(event -> {
                OffsetDateTime now = OffsetDateTime.now(clock);
                try {
                    sink.publish(event.eventId(), event.transferId(), event.eventType(), event.payload());
                    outbox.markPublished(event.eventId(), now);
                } catch (Exception e) {
                    LOG.warn("Outbox event {} not published; will retry", event.eventId(), e);
                    outbox.recordFailure(event.eventId(), now, props.outbox().maxAttempts());
                }
            }));
        }
    }
}
