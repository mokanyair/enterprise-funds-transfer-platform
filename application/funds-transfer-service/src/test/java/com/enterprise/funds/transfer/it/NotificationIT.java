package com.enterprise.funds.transfer.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.notifications.NotificationCategory;
import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.service.NotificationService;
import com.enterprise.funds.transfer.web.dto.NotificationDto;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The Notifications API against a real Oracle (V9, V11) through the runtime identity: customer scoping, ordering,
 * filters, and READ_AT updates that TRG_NOTIF_IMMUTABLE accepts. Rows are seeded through the owner identity, as the
 * consumer would have written them. Skipped unless FUNDS_IT_ENABLED=true; see README.
 */
@SpringBootTest(properties = {"funds.jobs.enabled=false", "FUNDS_JWT_ISSUER_URI=https://issuer.invalid/"})
@EnabledIfEnvironmentVariable(named = "FUNDS_IT_ENABLED", matches = "true")
class NotificationIT {

    private static final OffsetDateTime T0 = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);

    @Autowired NotificationService notifications;
    @MockitoBean JwtDecoder jwtDecoder;

    private OracleFixture db;
    private UUID customerId;
    private Actor alice;
    private Actor bob; // same customer as alice (N1)
    private Actor carol; // another customer

    @BeforeEach
    void fixtures() {
        db = new OracleFixture();
        customerId = db.customer();
        alice = db.customerUser(customerId);
        bob = db.customerUser(customerId);
        carol = db.customerUser(db.customer());
    }

    private UUID seed(UUID customer, String category, String type, OffsetDateTime createdAt) {
        UUID id = Ids.newId();
        boolean transfer = type.startsWith("TRANSFER");
        db.owner.update("""
                INSERT INTO NOTIFICATIONS (NOTIFICATION_ID, CUSTOMER_ID, CATEGORY, TYPE, SOURCE_EVENT_ID, ENTITY_TYPE,
                                           ENTITY_ID, PARAMS, OCCURRED_AT, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.toBytes(id), Ids.toBytes(customer), category, type, Ids.toBytes(Ids.newId()),
                transfer ? "TRANSFER" : "BENEFICIARY", Ids.toBytes(Ids.newId()),
                transfer ? "{\"amount\":\"25.00\",\"currency\":\"USD\",\"counterpartyLast4\":\"1001\",\"reference\":\"x\"}"
                        : "{\"accountIdLast4\":\"2001\"}",
                createdAt, createdAt);
        return id;
    }

    private OffsetDateTime readAt(UUID id) {
        return db.owner.queryForObject("SELECT READ_AT FROM NOTIFICATIONS WHERE NOTIFICATION_ID = ?",
                OffsetDateTime.class, Ids.toBytes(id));
    }

    @Test
    void listsTheCustomersInboxNewestFirstWithFiltersAndTheUnreadCount() {
        UUID older = seed(customerId, "TRANSACTION", "TRANSFER_SENT", T0);
        UUID newer = seed(customerId, "SECURITY", "BENEFICIARY_ADDED", T0.plusMinutes(1));
        seed(carol.customerId(), "TRANSACTION", "TRANSFER_RECEIVED", T0.plusMinutes(2));

        var page = notifications.list(bob, 0, 20, null, false);
        assertThat(page.items()).extracting(NotificationDto::notificationId)
                .containsExactly(newer.toString(), older.toString());
        assertThat(page.unreadCount()).isEqualTo(2);
        assertThat(page.items().get(1).params().has("reference")).as("not on the allow-list").isFalse();
        assertThat(page.items().get(1).params().get("amount").asText()).isEqualTo("25.00");

        assertThat(notifications.list(alice, 0, 20, NotificationCategory.SECURITY, false).items())
                .extracting(NotificationDto::notificationId).containsExactly(newer.toString());
        assertThat(notifications.list(alice, 1, 1, null, false).items())
                .extracting(NotificationDto::notificationId).containsExactly(older.toString());
        assertThat(notifications.list(alice, 5, 20, null, false).items()).isEmpty();
    }

    @Test
    void markReadSetsReadAtOnceForEveryUserOfTheCustomer() {
        UUID id = seed(customerId, "TRANSACTION", "TRANSFER_SENT", T0);

        notifications.markRead(alice, id);
        OffsetDateTime first = readAt(id);
        assertThat(first).isNotNull();
        notifications.markRead(bob, id); // already read: 204 again, and the trigger is never asked to move READ_AT

        assertThat(readAt(id)).isEqualTo(first);
        assertThat(notifications.unreadCount(bob).unreadCount()).isZero();
        assertThat(notifications.list(bob, 0, 20, null, true).items()).isEmpty();
    }

    @Test
    void anotherCustomersNotificationIsAnUnchanged404() {
        UUID carols = seed(carol.customerId(), "TRANSACTION", "TRANSFER_RECEIVED", T0);

        assertThatThrownBy(() -> notifications.markRead(alice, carols))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> notifications.markRead(alice, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(readAt(carols)).isNull();
    }

    @Test
    void markAllReadCoversOnlyThisCustomersRowsStoredByNow() {
        UUID a = seed(customerId, "TRANSACTION", "TRANSFER_SENT", T0);
        UUID b = seed(customerId, "SECURITY", "BENEFICIARY_DELETED", T0.plusMinutes(1));
        UUID later = seed(customerId, "TRANSACTION", "TRANSFER_RECEIVED", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        UUID carols = seed(carol.customerId(), "TRANSACTION", "TRANSFER_RECEIVED", T0);
        notifications.markRead(alice, a);
        OffsetDateTime aRead = readAt(a);

        notifications.markAllRead(bob);
        notifications.markAllRead(bob); // idempotent

        assertThat(readAt(a)).isEqualTo(aRead);
        assertThat(readAt(b)).isNotNull();
        assertThat(readAt(later)).as("stored after the request").isNull();
        assertThat(readAt(carols)).isNull();
        assertThat(notifications.unreadCount(alice).unreadCount()).isEqualTo(1);
    }

    @Test
    void theUnreadCountIsAnIndexOnlyScanOfTheUnreadIndex() {
        // One connection: PLAN_TABLE is session-private, and the fixture opens a connection per statement.
        List<String> plan = db.owner.execute((ConnectionCallback<List<String>>) c -> {
            try (Statement st = c.createStatement()) {
                st.execute("ALTER SESSION SET CURRENT_SCHEMA = " + System.getenv().getOrDefault("FUNDS_DB_SCHEMA", "FUNDS_OWNER"));
                st.execute("EXPLAIN PLAN FOR SELECT COUNT(*) FROM NOTIFICATIONS "
                        + "WHERE (CASE WHEN READ_AT IS NULL THEN CUSTOMER_ID END) = HEXTORAW('01')");
                List<String> steps = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT OPERATION || ' ' || OPTIONS || ' ' || OBJECT_NAME FROM PLAN_TABLE ORDER BY ID")) {
                    while (rs.next()) steps.add(rs.getString(1));
                }
                return steps;
            }
        });
        assertThat(plan).anySatisfy(step -> assertThat(step).isEqualTo("INDEX RANGE SCAN IX_NOTIF_UNREAD"));
        assertThat(plan).noneSatisfy(step -> assertThat(step).startsWith("TABLE ACCESS"));
    }
}
