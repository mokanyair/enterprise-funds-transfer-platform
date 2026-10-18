package com.enterprise.funds.transfer.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.funds.transfer.config.KafkaEventConfig;
import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.service.BeneficiaryService;
import com.enterprise.funds.transfer.service.TransferService;
import com.enterprise.funds.transfer.web.dto.BeneficiaryDto;
import com.enterprise.funds.transfer.web.dto.CreateBeneficiaryRequest;
import com.enterprise.funds.transfer.web.dto.CreateTransferRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The whole event path against a real Oracle and a real Kafka broker: outbox row -> publisher -> topic -> consumer
 * -> NOTIFICATIONS, plus redelivery and dead-lettering. Skipped unless FUNDS_IT_ENABLED=true and
 * FUNDS_IT_KAFKA_BOOTSTRAP_SERVERS is set. Topic names are unique per run so earlier runs cannot interfere.
 * The context is closed afterwards: it is the only one with the publisher and consumer on, and a cached copy
 * would keep publishing other test classes' outbox rows while they assert on them.
 */
@SpringBootTest(properties = {
        "funds.jobs.enabled=false", "FUNDS_JWT_ISSUER_URI=https://issuer.invalid/",
        "funds.outbox.publisher-enabled=true", "funds.outbox.poll-interval=200ms",
        "funds.notifications.consumer-enabled=true", "funds.notifications.max-attempts=2",
        "funds.notifications.retry-backoff=200ms", "funds.kafka.create-topics=true", "funds.kafka.partitions=1"})
@EnabledIfEnvironmentVariable(named = "FUNDS_IT_ENABLED", matches = "true")
@EnabledIfEnvironmentVariable(named = "FUNDS_IT_KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventPipelineIT {

    private static final String RUN = Long.toString(System.currentTimeMillis(), 36);
    private static final String TRANSFER_TOPIC = "it." + RUN + ".transfer";
    private static final String BENEFICIARY_TOPIC = "it." + RUN + ".beneficiary";
    private static final Duration WAIT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> System.getenv("FUNDS_IT_KAFKA_BOOTSTRAP_SERVERS"));
        registry.add("funds.outbox.topic", () -> TRANSFER_TOPIC);
        registry.add("funds.outbox.beneficiary-topic", () -> BENEFICIARY_TOPIC);
        registry.add("funds.notifications.group-id", () -> "it-" + RUN);
    }

    @Autowired TransferService transfers;
    @Autowired BeneficiaryService beneficiaries;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder jwtDecoder;

    private OracleFixture db;
    private UUID aliceCustomer;
    private UUID bobCustomer;
    private Actor alice;
    private OracleFixture.Account aliceAccount;
    private OracleFixture.Account bobAccount;

    @BeforeEach
    void fixtures() {
        db = new OracleFixture();
        aliceCustomer = db.customer();
        alice = db.customerUser(aliceCustomer);
        aliceAccount = db.account(aliceCustomer, "1000.00");
        bobCustomer = db.customer();
        bobAccount = db.account(bobCustomer, "0.00");
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private List<Map<String, Object>> notificationsFor(UUID customerId) {
        return db.owner.queryForList("SELECT TYPE, CATEGORY, ENTITY_TYPE, SOURCE_EVENT_ID, PARAMS FROM NOTIFICATIONS "
                + "WHERE CUSTOMER_ID = ? ORDER BY CREATED_AT", Ids.toBytes(customerId));
    }

    private UUID transfer(String amount) {
        return transfers.create(alice, UUID.randomUUID().toString(),
                new CreateTransferRequest(aliceAccount.number(), bobAccount.number(), amount, "USD", "secret reference"),
                "it-" + UUID.randomUUID()).transferId();
    }

    @Test
    void aCompletedTransferIsPublishedAndNotifiesSenderAndReceiver() throws Exception {
        UUID transferId = transfer("25.00");

        await("two notifications", () -> notificationsFor(aliceCustomer).size() == 1 && notificationsFor(bobCustomer).size() == 1);
        assertThat(notificationsFor(aliceCustomer).get(0)).containsEntry("TYPE", "TRANSFER_SENT").containsEntry("CATEGORY", "TRANSACTION");
        assertThat(notificationsFor(bobCustomer).get(0)).containsEntry("TYPE", "TRANSFER_RECEIVED");
        assertThat(String.valueOf(notificationsFor(bobCustomer).get(0).get("PARAMS")))
                .contains("\"amount\":\"25.00\"").doesNotContain("secret").doesNotContain(aliceAccount.number());

        String status = db.owner.queryForObject("SELECT STATUS FROM OUTBOX_EVENTS WHERE TRANSFER_ID = ?",
                String.class, Ids.toBytes(transferId));
        assertThat(status).isEqualTo("PUBLISHED");
    }

    @Test
    void beneficiaryChangesBecomeSecurityNotices() throws Exception {
        BeneficiaryDto saved = beneficiaries.create(alice, new CreateBeneficiaryRequest(bobAccount.number(), "Bob"), "it");
        beneficiaries.delete(alice, UUID.fromString(saved.beneficiaryId()), "it");

        await("added and deleted notices", () -> notificationsFor(aliceCustomer).size() == 2);
        assertThat(notificationsFor(aliceCustomer)).extracting(r -> r.get("TYPE"))
                .containsExactly("BENEFICIARY_ADDED", "BENEFICIARY_DELETED");
        assertThat(notificationsFor(aliceCustomer)).allSatisfy(r -> {
            assertThat(r).containsEntry("CATEGORY", "SECURITY").containsEntry("ENTITY_TYPE", "BENEFICIARY");
            assertThat(String.valueOf(r.get("PARAMS"))).doesNotContain("Bob");
        });
        assertThat(notificationsFor(bobCustomer)).as("the saved account's owner is not told").isEmpty();

        Map<String, Object> row = db.owner.queryForMap("SELECT AGGREGATE_TYPE, TRANSFER_ID, STATUS FROM OUTBOX_EVENTS "
                + "WHERE AGGREGATE_ID = ? AND EVENT_TYPE = 'BeneficiaryAdded'", Ids.toBytes(UUID.fromString(saved.beneficiaryId())));
        assertThat(row).containsEntry("AGGREGATE_TYPE", "BENEFICIARY").containsEntry("STATUS", "PUBLISHED");
        assertThat(row.get("TRANSFER_ID")).isNull();
    }

    @Test
    void aRedeliveredEventNotifiesNoOneTwice() throws Exception {
        UUID transferId = transfer("10.00");
        await("first delivery", () -> notificationsFor(bobCustomer).size() == 1);
        String payload = db.owner.queryForObject("SELECT PAYLOAD FROM OUTBOX_EVENTS WHERE TRANSFER_ID = ?",
                String.class, Ids.toBytes(transferId));

        // Same record again (what a publisher crash between send and mark-published produces), then a marker
        // event on the same key and partition: once the marker is projected, the duplicate before it has been too.
        kafka.send(TRANSFER_TOPIC, transferId.toString(), payload).get();
        ObjectNode marker = (ObjectNode) mapper.readTree(payload);
        UUID markerId = UUID.randomUUID();
        marker.put("eventId", markerId.toString());
        kafka.send(TRANSFER_TOPIC, transferId.toString(), marker.toString()).get();

        await("marker", () -> notificationsFor(bobCustomer).size() == 2);
        assertThat(notificationsFor(aliceCustomer)).hasSize(2); // original + marker, not 3
        assertThat(notificationsFor(bobCustomer)).hasSize(2);
    }

    @Test
    void aPoisonMessageIsDeadLetteredAndDoesNotBlockThePartition() throws Exception {
        kafka.send(TRANSFER_TOPIC, "poison", "this is not an event").get();
        transfer("5.00");

        await("the next event still flows", () -> notificationsFor(bobCustomer).size() == 1);

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getenv("FUNDS_IT_KAFKA_BOOTSTRAP_SERVERS"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-dlt-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaEventConfig.dltOf(TRANSFER_TOPIC)));
            Instant deadline = Instant.now().plus(WAIT);
            ConsumerRecord<String, String> dead = null;
            while (dead == null && Instant.now().isBefore(deadline)) {
                for (var r : consumer.poll(Duration.ofMillis(500))) {
                    if ("poison".equals(r.key())) dead = r;
                }
            }
            assertThat(dead).as("poison record on the DLT").isNotNull();
            assertThat(dead.value()).isEqualTo("this is not an event");
            assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        }
    }
}
