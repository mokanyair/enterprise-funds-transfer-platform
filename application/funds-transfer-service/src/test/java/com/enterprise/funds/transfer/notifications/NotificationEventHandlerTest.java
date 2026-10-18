package com.enterprise.funds.transfer.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.funds.transfer.persistence.AccountRepository;
import com.enterprise.funds.transfer.persistence.Rows.AccountRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationEventHandlerTest {

    static final class NoopTx extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) {}
        @Override protected void doCommit(DefaultTransactionStatus s) {}
        @Override protected void doRollback(DefaultTransactionStatus s) {}
    }

    private final NotificationRepository repo = mock(NotificationRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final NotificationEventHandler handler = new NotificationEventHandler(new ObjectMapper(), repo, accounts,
            new TransactionTemplate(new NoopTx()), Clock.systemUTC());

    private final UUID eventId = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private String completed(boolean withParties) {
        String parties = withParties
                ? ",\"parties\":[{\"customerId\":\"" + alice + "\",\"role\":\"SOURCE\"},{\"customerId\":\"" + bob + "\",\"role\":\"DESTINATION\"}]"
                : "";
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"TransferCompleted\",\"occurredAt\":\"2026-10-18T09:00:00Z\","
                + "\"transferId\":\"" + UUID.randomUUID() + "\",\"sourceAccountId\":\"ACC1001\",\"destinationAccountId\":\"ACC2002\","
                + "\"amount\":\"1.00\",\"currency\":\"USD\"" + parties + "}";
    }

    @Test
    void storesOneRowPerRecipient() {
        assertThat(handler.handle(completed(true))).isEqualTo(2);
        verify(repo, times(2)).insert(any(), anyString(), any());
    }

    @Test
    void aRedeliveredEventStoresNothing() {
        when(repo.exists(eq(eventId), any())).thenReturn(true);
        assertThat(handler.handle(completed(true))).isZero();
        verify(repo, never()).insert(any(), anyString(), any());
    }

    @Test
    void aPartlyStoredEventCompletesOnlyTheMissingRecipient() {
        when(repo.exists(eventId, alice)).thenReturn(true);
        assertThat(handler.handle(completed(true))).isEqualTo(1);
        verify(repo).insert(org.mockito.ArgumentMatchers.argThat(d -> d.customerId().equals(bob)), anyString(), any());
    }

    @Test
    void v0EventsAreRoutedByLookingUpTheAccounts() {
        when(accounts.findByNumber("ACC1001")).thenReturn(Optional.of(new AccountRow(UUID.randomUUID(), "ACC1001", alice, "USD", "ACTIVE")));
        when(accounts.findByNumber("ACC2002")).thenReturn(Optional.of(new AccountRow(UUID.randomUUID(), "ACC2002", bob, "USD", "ACTIVE")));
        assertThat(handler.handle(completed(false))).isEqualTo(2);
    }

    @Test
    void garbageIsMalformedAndTouchesNothing() {
        assertThatThrownBy(() -> handler.handle("not json")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> handler.handle("[1,2]")).isInstanceOf(MalformedEventException.class);
        verifyNoInteractions(repo);
    }
}
