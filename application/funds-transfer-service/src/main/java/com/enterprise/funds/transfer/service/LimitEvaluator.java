package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.persistence.Rows.LimitRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Pure limit arithmetic (design L4-L7). All applicable limits must hold, so the most restrictive wins.
 * Windows are UTC calendar days and months. Usage is supplied by the caller and must have been read
 * after the posting locks were taken, otherwise the concurrency guarantee is lost.
 */
public final class LimitEvaluator {

    @FunctionalInterface
    public interface UsageProvider {
        BigDecimal usage(LimitRow limit, OffsetDateTime windowStart);
    }

    private LimitEvaluator() {}

    /** @return the first limit the transfer would breach, if any (never shown to the caller) */
    public static Optional<LimitRow> firstViolation(List<LimitRow> limits, BigDecimal amount,
                                                    OffsetDateTime now, UsageProvider usage) {
        for (LimitRow limit : limits) {
            BigDecimal used = "PER_TRANSFER".equals(limit.period())
                    ? BigDecimal.ZERO
                    : usage.usage(limit, windowStart(limit.period(), now));
            if (used.add(amount).compareTo(limit.amount()) > 0) {
                return Optional.of(limit);
            }
        }
        return Optional.empty();
    }

    public static OffsetDateTime windowStart(String period, OffsetDateTime now) {
        OffsetDateTime utc = now.withOffsetSameInstant(ZoneOffset.UTC);
        return switch (period) {
            case "DAILY" -> utc.truncatedTo(ChronoUnit.DAYS);
            case "MONTHLY" -> utc.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            default -> throw new IllegalArgumentException("Unsupported limit period: " + period);
        };
    }
}
