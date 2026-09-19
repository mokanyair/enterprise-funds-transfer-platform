package com.enterprise.funds.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.funds.transfer.persistence.Rows.LimitRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LimitEvaluatorTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 18, 15, 30, 0, 0, ZoneOffset.UTC);

    private static LimitRow limit(String period, String amount) {
        return new LimitRow(UUID.randomUUID(), null, UUID.randomUUID(), period, new BigDecimal(amount));
    }

    @Test
    void dailyWindowStartsAtUtcMidnight() {
        assertThat(LimitEvaluator.windowStart("DAILY", NOW)).isEqualTo(OffsetDateTime.of(2026, 9, 18, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void monthlyWindowStartsOnTheFirst() {
        assertThat(LimitEvaluator.windowStart("MONTHLY", NOW)).isEqualTo(OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void windowsUseUtcNotTheCallersOffset() {
        OffsetDateTime lateEveningNewYork = OffsetDateTime.of(2026, 9, 18, 22, 0, 0, 0, ZoneOffset.ofHours(-4)); // 02:00Z on the 19th
        assertThat(LimitEvaluator.windowStart("DAILY", lateEveningNewYork))
                .isEqualTo(OffsetDateTime.of(2026, 9, 19, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void usagePlusAmountExactlyAtTheLimitIsAllowed() {
        var limits = List.of(limit("DAILY", "1000.00"));
        assertThat(LimitEvaluator.firstViolation(limits, new BigDecimal("400.00"), NOW, (l, from) -> new BigDecimal("600.00"))).isEmpty();
    }

    @Test
    void oneCentOverTheLimitIsRefused() {
        var limits = List.of(limit("DAILY", "1000.00"));
        assertThat(LimitEvaluator.firstViolation(limits, new BigDecimal("400.01"), NOW, (l, from) -> new BigDecimal("600.00"))).isPresent();
    }

    @Test
    void theRaceScenarioIsCaughtOnceUsageIsVisible() {
        // Two 600 transfers against a 1000 daily limit: the second must fail once the first is counted.
        var limits = List.of(limit("DAILY", "1000.00"));
        assertThat(LimitEvaluator.firstViolation(limits, new BigDecimal("600"), NOW, (l, from) -> BigDecimal.ZERO)).isEmpty();
        assertThat(LimitEvaluator.firstViolation(limits, new BigDecimal("600"), NOW, (l, from) -> new BigDecimal("600"))).isPresent();
    }

    @Test
    void perTransferLimitIgnoresUsageAndNeverQueriesIt() {
        var limits = List.of(limit("PER_TRANSFER", "500.00"));
        var queried = new ArrayList<String>();
        var result = LimitEvaluator.firstViolation(limits, new BigDecimal("500.01"), NOW, (l, from) -> {
            queried.add("called");
            return BigDecimal.ZERO;
        });
        assertThat(result).isPresent();
        assertThat(queried).isEmpty();
        assertThat(LimitEvaluator.firstViolation(limits, new BigDecimal("500.00"), NOW, (l, f) -> BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void theMostRestrictiveOfSeveralLimitsWins() {
        var daily = limit("DAILY", "10000.00");
        var monthly = limit("MONTHLY", "1200.00");
        var result = LimitEvaluator.firstViolation(List.of(daily, monthly), new BigDecimal("300"), NOW,
                (l, from) -> "MONTHLY".equals(l.period()) ? new BigDecimal("1000") : new BigDecimal("50"));
        assertThat(result).contains(monthly);
    }

    @Test
    void eachLimitReceivesItsOwnWindowStart() {
        var seen = new ArrayList<OffsetDateTime>();
        LimitEvaluator.firstViolation(List.of(limit("DAILY", "1"), limit("MONTHLY", "1000000")), BigDecimal.ONE, NOW,
                (l, from) -> {
                    seen.add(from);
                    return BigDecimal.ZERO;
                });
        assertThat(seen).containsExactly(OffsetDateTime.of(2026, 9, 18, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void noLimitsMeansNoViolation() {
        assertThat(LimitEvaluator.firstViolation(List.of(), new BigDecimal("999999"), NOW, (l, f) -> BigDecimal.ZERO)).isEmpty();
    }
}
