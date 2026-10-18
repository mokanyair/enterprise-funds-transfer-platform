package com.enterprise.funds.transfer.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.funds.transfer.TestProps;
import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.persistence.OutboxRepository;
import com.enterprise.funds.transfer.persistence.Rows.RawOutboxRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** No single outbox row may stop the events behind it (docs/release-2-event-streaming.md section 8). */
class OutboxPublisherTest {

    static final class NoopTx extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) {}
        @Override protected void doCommit(DefaultTransactionStatus s) {}
        @Override protected void doRollback(DefaultTransactionStatus s) {}
    }

    private final OutboxRepository repo = mock(OutboxRepository.class);
    private final EventSink sink = mock(EventSink.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T09:00:00Z"), ZoneOffset.UTC);
    private final OutboxPublisher publisher = new OutboxPublisher(repo, sink, new TransactionTemplate(new NoopTx()),
            TestProps.props(Duration.ofDays(7), Duration.ofSeconds(60), Duration.ofDays(90), new BigDecimal("10000"), null),
            clock);

    private static RawOutboxRow good(UUID id) {
        return new RawOutboxRow(Ids.toBytes(id), "TRANSFER", Ids.toBytes(id), "TransferCompleted", "{}");
    }

    private void pending(RawOutboxRow... rows) {
        when(repo.findPendingKeys(anyInt())).thenReturn(java.util.Arrays.stream(rows).map(RawOutboxRow::key).toList());
        for (RawOutboxRow r : rows) {
            when(repo.claim(r.key())).thenReturn(Optional.of(r));
        }
    }

    @Test
    void aMalformedRowIsQuarantinedAndTheRowsBehindItArePublished() throws Exception {
        RawOutboxRow malformed = new RawOutboxRow(new byte[] {(byte) 0xA6}, "TRANSFER", new byte[] {(byte) 0xA5}, "X", "{}");
        UUID next = UUID.randomUUID();
        pending(malformed, good(next));

        publisher.publishPending();

        verify(repo).quarantine(eq(malformed.key()), any());
        verify(sink, times(1)).publish(any(), anyString(), any(), anyString(), anyString());
        verify(sink).publish(any(), eq("TRANSFER"), eq(next), eq("TransferCompleted"), eq("{}"));
        verify(repo).markPublished(eq(Ids.toBytes(next)), any());
    }

    @Test
    void aRowThatCannotBeClaimedIsSkippedForThisPollOnly() throws Exception {
        UUID stuck = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        pending(good(stuck), good(next));
        when(repo.claim(Ids.toBytes(stuck))).thenThrow(new CannotAcquireLockException("ORA-30006"));

        publisher.publishPending();

        verify(repo, never()).quarantine(any(), any()); // not malformed: stays PENDING for the next poll
        verify(sink).publish(any(), anyString(), eq(next), anyString(), anyString());
    }

    @Test
    void aBrokerFailureCountsAnAttemptAndTheBatchContinues() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        pending(good(first), good(second));
        doThrow(new java.util.concurrent.TimeoutException("broker down"))
                .when(sink).publish(any(), anyString(), eq(first), anyString(), anyString());

        publisher.publishPending();

        verify(repo).recordFailure(eq(Ids.toBytes(first)), any(), eq(10));
        verify(repo, never()).markPublished(eq(Ids.toBytes(first)), any());
        verify(repo).markPublished(eq(Ids.toBytes(second)), any());
    }

    @Test
    void failedRowsAreReportedAtMostOncePerInterval() {
        when(repo.findPendingKeys(anyInt())).thenReturn(List.of());
        when(repo.countFailed()).thenReturn(2);

        publisher.publishPending();
        publisher.publishPending();

        verify(repo, times(1)).countFailed(); // clock is fixed: the second poll is inside the interval
    }

    @Test
    void aFailingHealthCountNeverBreaksPublishing() throws Exception {
        UUID id = UUID.randomUUID();
        pending(good(id));
        when(repo.countFailed()).thenThrow(new IllegalStateException("db down"));

        publisher.publishPending();

        verify(repo).markPublished(eq(Ids.toBytes(id)), any());
    }
}
