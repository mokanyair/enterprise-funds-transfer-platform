package com.enterprise.funds.transfer.jobs;

import com.enterprise.funds.transfer.config.FundsProperties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Keyed by transfer id so one transfer's events stay ordered. Waits for the broker ack (acks=all). */
@Component
@ConditionalOnProperty(prefix = "funds.outbox", name = "publisher-enabled", havingValue = "true")
public class KafkaEventSink implements EventSink {

    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaEventSink(KafkaTemplate<String, String> kafka, FundsProperties props) {
        this.kafka = kafka;
        this.topic = props.outbox().topic();
    }

    @Override
    public void publish(UUID eventId, UUID transferId, String eventType, String payloadJson) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, transferId.toString(), payloadJson);
        record.headers().add(new RecordHeader("eventId", eventId.toString().getBytes()));
        record.headers().add(new RecordHeader("eventType", eventType.getBytes()));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }
}
