package com.enterprise.funds.transfer.jobs;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.persistence.OutboxRepository;
import com.enterprise.funds.transfer.persistence.Rows.OutboxRow;
import com.enterprise.funds.transfer.persistence.Rows.RawOutboxRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 *
 * Head-of-line safety: rows are scanned as raw keys and decoded one at a time, and every row runs inside its own
 * try. A row that can never be published is quarantined (FAILED) and any other per-row error skips only that row for
 * this poll, so no single row can stop the events behind it.
 */
@Component
@ConditionalOnProperty(prefix = "funds.outbox", name = "publisher-enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    static final Duration FAILED_REPORT_INTERVAL = Duration.ofMinutes(1);

    private final OutboxRepository outbox;
    private final EventSink sink;
    private final TransactionTemplate tx;
    private final FundsProperties props;
    private final Clock clock;
    private Instant nextFailedReport = Instant.MIN;

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
        for (byte[] key : outbox.findPendingKeys(props.outbox().batchSize())) {
            try {
                tx.executeWithoutResult(status -> outbox.claim(key).ifPresent(this::publishOne));
            } catch (RuntimeException e) {
                // Claim or bookkeeping failed (e.g. a lock timeout, a dropped connection). Rolled back; the row stays
                // PENDING and is retried next poll. The rest of the batch continues.
                LOG.error("Outbox row {} could not be processed this poll", hex(key), e);
            }
        }
        reportFailed();
    }

    private void publishOne(RawOutboxRow raw) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OutboxRow event;
        try {
            event = raw.decode();
        } catch (IllegalArgumentException e) {
            LOG.error("Outbox row {} is malformed ({}); quarantined as FAILED", raw.keyHex(), e.getMessage());
            outbox.quarantine(raw.key(), now);
            return;
        }
        try {
            sink.publish(event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(), event.payload());
            outbox.markPublished(raw.key(), now);
        } catch (Exception e) {
            LOG.warn("Outbox event {} not published; will retry", event.eventId(), e);
            outbox.recordFailure(raw.key(), now, props.outbox().maxAttempts());
        }
    }

    /** At most once a minute: FAILED rows need a human (fix the cause, set them back to PENDING). Alert on this log. */
    private void reportFailed() {
        Instant now = clock.instant();
        if (now.isBefore(nextFailedReport)) {
            return;
        }
        nextFailedReport = now.plus(FAILED_REPORT_INTERVAL);
        try {
            int failed = outbox.countFailed();
            if (failed > 0) {
                LOG.error("{} outbox event(s) are FAILED and will not be published until requeued", failed);
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not count FAILED outbox events", e);
        }
    }

    private static String hex(byte[] key) {
        return key == null ? "null" : java.util.HexFormat.of().formatHex(key);
    }
}
