package com.enterprise.funds.transfer.notifications;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes both domain topics into NOTIFICATIONS (E5). Lives in this service for now but depends only on the event
 * contract, NOTIFICATIONS and (for v0 events) account lookup, so it can move to its own deployable unchanged.
 * Failures are retried, then dead-lettered (KafkaEventConfig).
 */
@Component
@ConditionalOnProperty(prefix = "funds.notifications", name = "consumer-enabled", havingValue = "true")
public class NotificationEventListener {

    private final NotificationEventHandler handler;

    public NotificationEventListener(NotificationEventHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(
            id = "notifications",
            groupId = "${funds.notifications.group-id:funds-notifications}",
            topics = {"${funds.outbox.topic:funds.transfer.events}", "${funds.outbox.beneficiary-topic:funds.beneficiary.events}"})
    public void onEvent(ConsumerRecord<String, String> record) {
        handler.handle(record.value());
    }
}
