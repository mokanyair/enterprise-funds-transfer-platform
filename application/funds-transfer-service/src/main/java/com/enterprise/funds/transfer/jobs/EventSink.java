package com.enterprise.funds.transfer.jobs;

import java.util.UUID;

/** Where outbox events go. Kafka in production; a test double elsewhere. Must throw on failure. */
public interface EventSink {
    /** aggregateId is the message key, so one aggregate's events stay ordered on one partition. */
    void publish(UUID eventId, String aggregateType, UUID aggregateId, String eventType, String payloadJson) throws Exception;
}
