package com.enterprise.funds.transfer.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FundsPropertiesValidatorTest {

    private static FundsProperties props(Duration replay, Duration lease, Duration tombstone, BigDecimal review, BigDecimal reject) {
        return com.enterprise.funds.transfer.TestProps.props(replay, lease, tombstone, review, reject);
    }

    private static void validate(FundsProperties p) {
        new FundsPropertiesValidator(p).afterPropertiesSet();
    }

    @Test
    void defaultsAreAccepted() {
        assertThatCode(() -> validate(props(Duration.ofDays(7), Duration.ofSeconds(60), Duration.ofDays(90),
                new BigDecimal("10000"), null))).doesNotThrowAnyException();
    }

    @Test
    void aKeyMustNotBeForgottenBeforeItsResponseExpires() {
        assertThatThrownBy(() -> validate(props(Duration.ofDays(7), Duration.ofSeconds(60), Duration.ofDays(3),
                new BigDecimal("10000"), null))).hasMessageContaining("tombstone-retention");
    }

    @Test
    void leaseMustExceedTheRequestDeadline() {
        assertThatThrownBy(() -> validate(props(Duration.ofDays(7), Duration.ofSeconds(10), Duration.ofDays(90),
                new BigDecimal("10000"), null))).hasMessageContaining("lease");
    }

    @Test
    void rejectThresholdCannotBeBelowReviewThreshold() {
        assertThatThrownBy(() -> validate(props(Duration.ofDays(7), Duration.ofSeconds(60), Duration.ofDays(90),
                new BigDecimal("10000"), new BigDecimal("5000")))).hasMessageContaining("reject-threshold");
    }
}
