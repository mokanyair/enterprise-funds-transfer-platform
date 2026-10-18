package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Plain read models for the JDBC repositories. */
public final class Rows {

    private Rows() {}

    public record AccountRow(UUID id, String number, UUID customerId, String currency, String status) {
        public boolean isActive() { return "ACTIVE".equals(status); }
    }

    public record BalanceRow(UUID accountId, BigDecimal available, BigDecimal ledger, long version) {}

    /** A transfer with both accounts' public numbers and owning customers, for display and access checks. */
    public record TransferView(
            UUID id,
            UUID sourceAccountId, String sourceNumber, UUID sourceCustomerId,
            UUID destinationAccountId, String destinationNumber, UUID destinationCustomerId,
            BigDecimal amount, String currency, TransferStatus status, String reference,
            UUID createdByUserId, UUID reversalOf,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record LimitRow(UUID id, UUID customerId, UUID accountId, String period, BigDecimal amount) {}

    public record IdempotencyRow(
            UUID id, UUID userId, UUID key, byte[] requestHash, String status, int attempt,
            OffsetDateTime leaseExpiresAt, UUID transferId, Integer responseStatus, String responseBody,
            OffsetDateTime replayExpiresAt, OffsetDateTime responsePurgedAt) {}

    /** A beneficiary with its destination's public account number. */
    public record BeneficiaryRow(
            UUID id, UUID customerId, UUID accountId, String accountNumber, String nickname, String status,
            OffsetDateTime createdAt) {
        public boolean isActive() { return "ACTIVE".equals(status); }
    }

    /** aggregateType is TRANSFER or BENEFICIARY (V8); it selects the Kafka topic and is the message key's owner. */
    public record OutboxRow(UUID eventId, String aggregateType, UUID aggregateId, String eventType, String payload) {}

    /**
     * An outbox row exactly as stored. The publisher decodes it row by row ({@link #decode()}), so one row that cannot
     * be decoded is quarantined instead of failing the whole poll and blocking every event behind it.
     */
    public record RawOutboxRow(byte[] key, String aggregateType, byte[] aggregateId, String eventType, String payload) {
        /** Throws IllegalArgumentException if an id is not a 16-byte RAW. */
        public OutboxRow decode() {
            return new OutboxRow(Ids.fromBytes(key), aggregateType, Ids.fromBytes(aggregateId), eventType, payload);
        }

        public String keyHex() {
            return java.util.HexFormat.of().formatHex(key);
        }
    }
}
