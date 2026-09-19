package com.enterprise.funds.transfer;

import com.enterprise.funds.transfer.config.FundsProperties;
import java.math.BigDecimal;
import java.time.Duration;

/** Builds FundsProperties for tests that do not start Spring. */
public final class TestProps {

    private TestProps() {}

    public static FundsProperties props(Duration replay, Duration lease, Duration tombstone,
                                        BigDecimal review, BigDecimal reject) {
        return new FundsProperties(Duration.ofSeconds(10), new FundsProperties.Db(5),
                new FundsProperties.Idempotency(replay, lease, tombstone, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                new FundsProperties.Risk(review, reject),
                new FundsProperties.Outbox(false, "t", 100, 10, Duration.ofSeconds(1)),
                new FundsProperties.Jobs(true, Duration.ofSeconds(30), Duration.ofMinutes(10), 500, false));
    }
}
