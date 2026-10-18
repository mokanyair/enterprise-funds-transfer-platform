package com.enterprise.funds.transfer.jobs;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.events.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * One topic per aggregate type (decision E3), keyed by aggregate id so one transfer's or beneficiary's events stay
 * ordered. Waits for the broker ack (acks=all); a failure leaves the outbox row PENDING for the next poll.
 */
@Component
@ConditionalOnProperty(prefix = "funds.outbox", name = "publisher-enabled", havingValue = "true")
public class KafkaEventSink implements EventSink {

    private final KafkaTemplate<String, String> kafka;
    private final FundsProperties.Outbox config;

    public KafkaEventSink(KafkaTemplate<String, String> kafka, FundsProperties props) {
        this.kafka = kafka;
        this.config = props.outbox();
    }

    @Override
    public void publish(UUID eventId, String aggregateType, UUID aggregateId, String eventType, String payloadJson)
            throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(config.topicFor(aggregateType), aggregateId.toString(), payloadJson);
        record.headers().add(header("eventId", eventId.toString()));
        record.headers().add(header("eventType", eventType));
        record.headers().add(header("aggregateType", aggregateType));
        record.headers().add(header("schemaVersion", Integer.toString(EventEnvelope.SCHEMA_VERSION)));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
