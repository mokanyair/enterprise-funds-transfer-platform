package com.enterprise.funds.transfer.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.funds.transfer.TestProps;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaEventSinkTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    // TestProps names the topics "t" (transfers) and "b" (beneficiaries).
    private final KafkaEventSink sink = new KafkaEventSink(kafka, TestProps.props(Duration.ofDays(7), Duration.ofSeconds(60),
            Duration.ofDays(90), new BigDecimal("10000"), null));

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sent(String aggregateType, UUID aggregateId) throws Exception {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        sink.publish(UUID.randomUUID(), aggregateType, aggregateId, "SomethingHappened", "{}");
        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(record.capture());
        return record.getValue();
    }

    private static String header(ProducerRecord<String, String> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @Test
    void routesByAggregateTypeKeyedByAggregateIdWithEnvelopeHeaders() throws Exception {
        UUID id = UUID.randomUUID();
        ProducerRecord<String, String> r = sent("BENEFICIARY", id);
        assertThat(r.topic()).isEqualTo("b");
        assertThat(r.key()).isEqualTo(id.toString());
        assertThat(header(r, "eventType")).isEqualTo("SomethingHappened");
        assertThat(header(r, "aggregateType")).isEqualTo("BENEFICIARY");
        assertThat(header(r, "schemaVersion")).isEqualTo("1");
        assertThat(r.headers().lastHeader("eventId")).isNotNull();
    }

    @Test
    void transferEventsStayOnTheTransferTopic() throws Exception {
        assertThat(sent("TRANSFER", UUID.randomUUID()).topic()).isEqualTo("t");
    }

    @Test
    void anUnknownAggregateIsRefusedSoTheRowStaysPending() {
        assertThatThrownBy(() -> sink.publish(UUID.randomUUID(), "MYSTERY", UUID.randomUUID(), "X", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
