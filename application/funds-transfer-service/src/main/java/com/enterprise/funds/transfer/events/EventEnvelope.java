package com.enterprise.funds.transfer.events;

/**
 * The event contract shared by the outbox writer and every consumer (docs/release-2-event-streaming.md, E2).
 *
 * Every event is one JSON object carrying the envelope fields below, plus the aggregate's own fields at the top
 * level. Transfer events keep the exact v0 fields (the TransferDto shape) so nothing that read them breaks.
 * {@code parties} names the customers an event concerns, with their role, so consumers never need the
 * transfer service's database to route it.
 *
 * Compatibility rule: additive changes keep {@link #SCHEMA_VERSION}; removing or changing a field's meaning bumps
 * it. Consumers ignore unknown fields and unknown event types.
 */
public final class EventEnvelope {

    public static final int SCHEMA_VERSION = 1;

    // Aggregate types (OUTBOX_EVENTS.AGGREGATE_TYPE, CK_OUTBOX_AGG_TYPE)
    public static final String TRANSFER = "TRANSFER";
    public static final String BENEFICIARY = "BENEFICIARY";

    // Event types
    public static final String TRANSFER_COMPLETED = "TransferCompleted";
    public static final String TRANSFER_PENDING_REVIEW = "TransferPendingReview";
    public static final String TRANSFER_REJECTED = "TransferRejected";
    public static final String TRANSFER_CANCELLED = "TransferCancelled";
    public static final String BENEFICIARY_ADDED = "BeneficiaryAdded";
    public static final String BENEFICIARY_DELETED = "BeneficiaryDeleted";

    // Party roles
    public static final String ROLE_SOURCE = "SOURCE";
    public static final String ROLE_DESTINATION = "DESTINATION";
    public static final String ROLE_OWNER = "OWNER";

    // Envelope field names
    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String VERSION = "schemaVersion";
    public static final String OCCURRED_AT = "occurredAt";
    public static final String AGGREGATE_TYPE = "aggregateType";
    public static final String AGGREGATE_ID = "aggregateId";
    public static final String PARTIES = "parties";

    private EventEnvelope() {}
}
