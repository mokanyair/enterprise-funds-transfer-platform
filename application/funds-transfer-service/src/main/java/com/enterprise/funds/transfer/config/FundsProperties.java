package com.enterprise.funds.transfer.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** All tunables for the service. Defaults mirror docs/milestone-1-contract.md section 4c. */
@Validated
@ConfigurationProperties(prefix = "funds")
public record FundsProperties(
        @DefaultValue("10s") @NotNull Duration requestDeadline,
        @DefaultValue Db db,
        @DefaultValue Idempotency idempotency,
        @DefaultValue Risk risk,
        @DefaultValue Outbox outbox,
        @DefaultValue Jobs jobs,
        @DefaultValue Cors cors,
        @DefaultValue Beneficiaries beneficiaries,
        @DefaultValue Kafka kafka,
        @DefaultValue Notifications notifications) {

    public record Db(@DefaultValue("5") @Min(1) @Max(60) int lockWaitSeconds) {}

    public record Idempotency(
            @DefaultValue("7d") @NotNull Duration replayRetention,
            @DefaultValue("60s") @NotNull Duration lease,
            @DefaultValue("90d") @NotNull Duration tombstoneRetention,
            @DefaultValue("1s") @NotNull Duration inProgressRetryAfter,
            @DefaultValue("2m") @NotNull Duration abortGrace) {}

    public record Risk(
            @DefaultValue("10000.00") @NotNull BigDecimal reviewThreshold,
            BigDecimal rejectThreshold) {}

    /** topic is the transfer topic (name kept from V1); beneficiaryTopic was added with Release 2 (E3). */
    public record Outbox(
            @DefaultValue("false") boolean publisherEnabled,
            @DefaultValue("funds.transfer.events") String topic,
            @DefaultValue("funds.beneficiary.events") String beneficiaryTopic,
            @DefaultValue("100") @Min(1) int batchSize,
            @DefaultValue("10") @Min(1) int maxAttempts,
            @DefaultValue("1s") @NotNull Duration pollInterval) {

        public String topicFor(String aggregateType) {
            return switch (aggregateType) {
                case "TRANSFER" -> topic;
                case "BENEFICIARY" -> beneficiaryTopic;
                default -> throw new IllegalArgumentException("No topic for aggregate type " + aggregateType);
            };
        }

        public java.util.List<String> allTopics() {
            return java.util.List.of(topic, beneficiaryTopic);
        }
    }

    /**
     * Topic provisioning (E4). Off by default: in shared environments topics are created by the platform with the
     * right replication. On for a single-broker dev box, where the app may create its own topics and DLTs.
     */
    public record Kafka(
            @DefaultValue("false") boolean createTopics,
            @DefaultValue("3") @Min(1) int partitions,
            @DefaultValue("1") @Min(1) short replicationFactor) {}

    /** The event consumer that projects events into NOTIFICATIONS (E5). Off by default, like the publisher. */
    public record Notifications(
            @DefaultValue("false") boolean consumerEnabled,
            @DefaultValue("funds-notifications") String groupId,
            @DefaultValue("5") @Min(1) int maxAttempts,
            @DefaultValue("2s") @NotNull Duration retryBackoff) {}

    public record Jobs(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") @NotNull Duration reconcileInterval,
            @DefaultValue("10m") @NotNull Duration purgeInterval,
            @DefaultValue("500") @Min(1) int purgeBatch,
            @DefaultValue("false") boolean anomalyChecks) {}

    /**
     * Caps from decision B5 (docs/release-2-beneficiaries-contract.md). maxActive also bounds the unpaginated
     * list; the OpenAPI spec documents 50, so raise both together.
     */
    public record Beneficiaries(
            @DefaultValue("50") @Min(1) @Max(500) int maxActive,
            @DefaultValue("20") @Min(1) int maxAddsPerDay) {}

    /** Empty by default: no browser origin is allowed to call the API until one is explicitly configured. */
    public record Cors(List<String> allowedOrigins) {
        public List<String> allowedOriginsOrEmpty() {
            return allowedOrigins == null ? List.of() : allowedOrigins;
        }
    }
}
