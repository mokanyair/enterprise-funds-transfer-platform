package com.enterprise.funds.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.enterprise.funds.transfer.domain.TransferStatus;
import com.enterprise.funds.transfer.persistence.OutboxRepository;
import com.enterprise.funds.transfer.persistence.Rows.BeneficiaryRow;
import com.enterprise.funds.transfer.persistence.Rows.TransferView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The event envelope (docs/release-2-event-streaming.md, E2) as actually written to OUTBOX_EVENTS. */
class OutboxWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private final OutboxRepository repo = mock(OutboxRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final OutboxWriter writer = new OutboxWriter(repo, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID srcCustomer = UUID.randomUUID();
    private final UUID dstCustomer = UUID.randomUUID();

    private JsonNode captured(String aggregateType, UUID aggregateId, String eventType) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> eventId = ArgumentCaptor.forClass(UUID.class);
        verify(repo).insert(eventId.capture(), eq(aggregateType), eq(aggregateId), eq(eventType), payload.capture(), any());
        JsonNode json = mapper.readTree(payload.getValue());
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.getValue().toString());
        return json;
    }

    @Test
    void transferEventsKeepTheV0FieldsAndAddEnvelopeAndParties() throws Exception {
        UUID id = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        var view = new TransferView(id, UUID.randomUUID(), "ACC001", srcCustomer, UUID.randomUUID(), "ACC002", dstCustomer,
                new BigDecimal("250.00"), "USD", TransferStatus.COMPLETED, "secret reference", UUID.randomUUID(), null, t, t);

        writer.writeTransfer("TransferCompleted", view);

        JsonNode json = captured("TRANSFER", id, "TransferCompleted");
        // v0 fields, unchanged
        assertThat(json.get("transferId").asText()).isEqualTo(id.toString());
        assertThat(json.get("sourceAccountId").asText()).isEqualTo("ACC001");
        assertThat(json.get("destinationAccountId").asText()).isEqualTo("ACC002");
        assertThat(json.get("amount").asText()).isEqualTo("250.00");
        assertThat(json.get("status").asText()).isEqualTo("COMPLETED");
        // envelope
        assertThat(json.get("eventType").asText()).isEqualTo("TransferCompleted");
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-10-18T09:00:00Z");
        assertThat(json.get("aggregateType").asText()).isEqualTo("TRANSFER");
        assertThat(json.get("aggregateId").asText()).isEqualTo(id.toString());
        assertThat(json.get("parties")).hasSize(2);
        assertThat(json.get("parties").get(0).get("customerId").asText()).isEqualTo(srcCustomer.toString());
        assertThat(json.get("parties").get(0).get("role").asText()).isEqualTo("SOURCE");
        assertThat(json.get("parties").get(1).get("role").asText()).isEqualTo("DESTINATION");
        // never free text
        assertThat(json.toString()).doesNotContain("secret reference");
    }

    @Test
    void beneficiaryEventsCarryOnlyTheLastFourAndNeverTheNickname() throws Exception {
        UUID id = UUID.randomUUID();
        var row = new BeneficiaryRow(id, srcCustomer, UUID.randomUUID(), "ACC12345", "Rent - landlord", "ACTIVE",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));

        writer.writeBeneficiary("BeneficiaryAdded", row);

        JsonNode json = captured("BENEFICIARY", id, "BeneficiaryAdded");
        assertThat(json.get("beneficiaryId").asText()).isEqualTo(id.toString());
        assertThat(json.get("accountIdLast4").asText()).isEqualTo("2345");
        assertThat(json.get("parties").get(0).get("role").asText()).isEqualTo("OWNER");
        assertThat(json.get("parties").get(0).get("customerId").asText()).isEqualTo(srcCustomer.toString());
        assertThat(json.toString()).doesNotContain("Rent").doesNotContain("ACC12345");
    }
}
