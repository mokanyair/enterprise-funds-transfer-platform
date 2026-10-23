package com.enterprise.funds.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.domain.Role;
import com.enterprise.funds.transfer.notifications.NotificationCategory;
import com.enterprise.funds.transfer.notifications.NotificationRepository;
import com.enterprise.funds.transfer.notifications.NotificationRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private final NotificationRepository repo = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(Clock.fixed(NOW, ZoneOffset.UTC), repo,
            new ObjectMapper());
    private final UUID customer = UUID.randomUUID();
    private final Actor actor = new Actor(UUID.randomUUID(), Role.CUSTOMER, customer);
    private final Actor operator = new Actor(UUID.randomUUID(), Role.OPERATOR, null);

    private static NotificationRow row(String params, OffsetDateTime readAt) {
        OffsetDateTime t = OffsetDateTime.parse("2026-10-18T08:59:59.123456Z");
        return new NotificationRow(UUID.randomUUID(), "TRANSACTION", "TRANSFER_RECEIVED", "TRANSFER", UUID.randomUUID(),
                params, t, t, readAt);
    }

    @Test
    void listsAPageScopedToTheCallersCustomerWithTheTotalUnreadCount() {
        when(repo.list(customer, "SECURITY", true, 40, 20)).thenReturn(List.of(row("{}", null)));
        when(repo.count(customer, "SECURITY", true)).thenReturn(41L);
        when(repo.countUnread(customer)).thenReturn(7L);

        var page = service.list(actor, 2, 20, NotificationCategory.SECURITY, true);

        assertThat(page.items()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.unreadCount()).isEqualTo(7);
    }

    @Test
    void aHugePageNumberDoesNotOverflowTheOffset() {
        service.list(actor, Integer.MAX_VALUE, 50, null, false);
        verify(repo).list(customer, null, false, (long) Integer.MAX_VALUE * 50, 50);
    }

    @Test
    void onlyAllowListedStringParamsAreSent() {
        var dto = service.toDto(row("{\"transferId\":\"t\",\"amount\":\"25.00\",\"currency\":\"USD\","
                + "\"counterpartyLast4\":\"1001\",\"reference\":\"secret\",\"nickname\":\"Bob\",\"accountIdLast4\":7}", null));
        assertThat(dto.params().properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("transferId", "amount", "currency", "counterpartyLast4");
        assertThat(dto.params().toString()).doesNotContain("secret").doesNotContain("Bob");
    }

    @Test
    void anUnreadableParamsRowStillListsWithEmptyParams() {
        assertThat(service.toDto(row("not json", null)).params().isEmpty()).isTrue();
    }

    @Test
    void timesAreMillisecondInstantsAndUnreadIsANullReadAt() {
        var dto = service.toDto(row("{}", null));
        assertThat(dto.createdAt()).isEqualTo(Instant.parse("2026-10-18T08:59:59.123Z"));
        assertThat(dto.readAt()).isNull();
    }

    @Test
    void markReadIsIdempotent() {
        UUID id = UUID.randomUUID();
        when(repo.markRead(eq(customer), eq(id), any())).thenReturn(false);
        when(repo.existsFor(customer, id)).thenReturn(true); // already read
        service.markRead(actor, id);
        verify(repo).markRead(customer, id, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void markReadOfAnUnknownOrAnotherCustomersNotificationIs404() {
        UUID id = UUID.randomUUID();
        when(repo.markRead(eq(customer), eq(id), any())).thenReturn(false);
        when(repo.existsFor(customer, id)).thenReturn(false);
        assertThatThrownBy(() -> service.markRead(actor, id))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void markAllReadUsesTheRequestTime() {
        service.markAllRead(actor);
        verify(repo).markAllRead(customer, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void operatorsAreForbiddenEverywhereAndNothingIsRead() {
        for (Runnable call : List.<Runnable>of(
                () -> service.list(operator, 0, 20, null, false),
                () -> service.unreadCount(operator),
                () -> service.markRead(operator, UUID.randomUUID()),
                () -> service.markAllRead(operator))) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        verifyNoInteractions(repo);
    }
}
