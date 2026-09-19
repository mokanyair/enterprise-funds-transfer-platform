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

    public record OutboxRow(UUID eventId, UUID transferId, String eventType, String payload) {}
}
