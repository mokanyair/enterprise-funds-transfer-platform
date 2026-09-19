package com.enterprise.funds.transfer.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
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
        @DefaultValue Jobs jobs) {

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

    public record Outbox(
            @DefaultValue("false") boolean publisherEnabled,
            @DefaultValue("funds.transfer.events") String topic,
            @DefaultValue("100") @Min(1) int batchSize,
            @DefaultValue("10") @Min(1) int maxAttempts,
            @DefaultValue("1s") @NotNull Duration pollInterval) {}

    public record Jobs(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") @NotNull Duration reconcileInterval,
            @DefaultValue("10m") @NotNull Duration purgeInterval,
            @DefaultValue("500") @Min(1) int purgeBatch,
            @DefaultValue("false") boolean anomalyChecks) {}
}
