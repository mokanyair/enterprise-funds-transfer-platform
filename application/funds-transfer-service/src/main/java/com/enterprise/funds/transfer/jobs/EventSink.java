package com.enterprise.funds.transfer.jobs;

import java.util.UUID;

/** Where outbox events go. Kafka in production; a test double elsewhere. Must throw on failure. */
public interface EventSink {
    void publish(UUID eventId, UUID transferId, String eventType, String payloadJson) throws Exception;
}
