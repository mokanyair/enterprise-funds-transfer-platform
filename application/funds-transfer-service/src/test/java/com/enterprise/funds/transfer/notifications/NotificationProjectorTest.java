package com.enterprise.funds.transfer.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NotificationProjectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NotificationProjector projector = new NotificationProjector(mapper);
    private final UUID eventId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private ObjectNode transferEvent(String type, UUID source, UUID destination) {
        ObjectNode e = mapper.createObjectNode();
        e.put("eventId", eventId.toString()).put("eventType", type).put("schemaVersion", 1)
                .put("occurredAt", "2026-10-18T09:00:00Z").put("aggregateType", "TRANSFER")
                .put("aggregateId", transferId.toString()).put("transferId", transferId.toString())
                .put("sourceAccountId", "ACC1001").put("destinationAccountId", "ACC2002")
                .put("amount", "25.50").put("currency", "USD").put("status", "COMPLETED");
        var parties = e.putArray("parties");
        parties.addObject().put("customerId", source.toString()).put("role", "SOURCE");
        parties.addObject().put("customerId", destination.toString()).put("role", "DESTINATION");
        return e;
    }

    @Test
    void aCompletedTransferNotifiesSenderAndReceiverWithTheOtherSidesLastFour() {
        List<NotificationDraft> out = projector.project(transferEvent("TransferCompleted", alice, bob), null);
        assertThat(out).hasSize(2);
        NotificationDraft sent = out.get(0);
        NotificationDraft received = out.get(1);
        assertThat(sent.customerId()).isEqualTo(alice);
        assertThat(sent.type()).isEqualTo("TRANSFER_SENT");
        assertThat(sent.category()).isEqualTo("TRANSACTION");
        assertThat(sent.params().get("counterpartyLast4").asText()).isEqualTo("2002");
        assertThat(received.customerId()).isEqualTo(bob);
        assertThat(received.type()).isEqualTo("TRANSFER_RECEIVED");
        assertThat(received.params().get("counterpartyLast4").asText()).isEqualTo("1001");
        assertThat(out).allSatisfy(d -> {
            assertThat(d.sourceEventId()).isEqualTo(eventId);
            assertThat(d.entityType()).isEqualTo("TRANSFER");
            assertThat(d.entityId()).isEqualTo(transferId);
            assertThat(d.params().get("amount").asText()).isEqualTo("25.50");
        });
    }

    @Test
    void aTransferBetweenOwnAccountsNotifiesOnce() {
        assertThat(projector.project(transferEvent("TransferCompleted", alice, alice), null))
                .singleElement().satisfies(d -> assertThat(d.type()).isEqualTo("TRANSFER_SENT"));
    }

    @ParameterizedTest
    @CsvSource({"TransferPendingReview,TRANSFER_UNDER_REVIEW", "TransferRejected,TRANSFER_REJECTED",
            "TransferCancelled,TRANSFER_CANCELLED"})
    void otherTransferOutcomesNotifyOnlyTheSender(String eventType, String notificationType) {
        assertThat(projector.project(transferEvent(eventType, alice, bob), null))
                .singleElement().satisfies(d -> {
                    assertThat(d.customerId()).isEqualTo(alice);
                    assertThat(d.type()).isEqualTo(notificationType);
                });
    }

    @ParameterizedTest
    @CsvSource({"BeneficiaryAdded,BENEFICIARY_ADDED", "BeneficiaryDeleted,BENEFICIARY_DELETED"})
    void beneficiaryChangesAreSecurityNotices(String eventType, String notificationType) {
        UUID beneficiaryId = UUID.randomUUID();
        ObjectNode e = mapper.createObjectNode();
        e.put("eventId", eventId.toString()).put("eventType", eventType).put("schemaVersion", 1)
                .put("occurredAt", "2026-10-18T09:00:00Z").put("beneficiaryId", beneficiaryId.toString())
                .put("accountIdLast4", "2002");
        e.putArray("parties").addObject().put("customerId", alice.toString()).put("role", "OWNER");

        assertThat(projector.project(e, null)).singleElement().satisfies(d -> {
            assertThat(d.customerId()).isEqualTo(alice);
            assertThat(d.category()).isEqualTo("SECURITY");
            assertThat(d.type()).isEqualTo(notificationType);
            assertThat(d.entityType()).isEqualTo("BENEFICIARY");
            assertThat(d.entityId()).isEqualTo(beneficiaryId);
            assertThat(d.params().get("accountIdLast4").asText()).isEqualTo("2002");
        });
    }

    @Test
    void unknownEventTypesNotifyNoOne() {
        assertThat(projector.project(transferEvent("TransferSomethingNew", alice, bob), null)).isEmpty();
    }

    @Test
    void v0EventsWithoutPartiesResolveRecipientsThroughTheResolver() {
        ObjectNode legacy = transferEvent("TransferCompleted", alice, bob);
        legacy.remove("parties");
        legacy.remove("schemaVersion");
        NotificationProjector.PartyResolver resolver = event -> Map.of("SOURCE", alice, "DESTINATION", bob);
        assertThat(projector.project(legacy, resolver)).extracting(NotificationDraft::customerId).containsExactly(alice, bob);
    }

    @Test
    void malformedEventsAreRejectedAsNonRetryable() {
        ObjectNode noId = transferEvent("TransferCompleted", alice, bob);
        noId.remove("eventId");
        assertThatThrownBy(() -> projector.project(noId, null)).isInstanceOf(MalformedEventException.class);

        ObjectNode badTime = transferEvent("TransferCompleted", alice, bob);
        badTime.put("occurredAt", "yesterday");
        assertThatThrownBy(() -> projector.project(badTime, null)).isInstanceOf(MalformedEventException.class);

        ObjectNode noRecipient = transferEvent("TransferCompleted", alice, bob);
        noRecipient.remove("parties");
        assertThatThrownBy(() -> projector.project(noRecipient, e -> Map.of()))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    void paramsNeverCarryFreeTextOrFullAccountNumbers() {
        ObjectNode e = transferEvent("TransferCompleted", alice, bob);
        e.put("reference", "secret note");
        for (NotificationDraft d : projector.project(e, null)) {
            String params = d.params().toString();
            assertThat(params).doesNotContain("secret").doesNotContain("ACC1001").doesNotContain("ACC2002");
        }
    }

}
