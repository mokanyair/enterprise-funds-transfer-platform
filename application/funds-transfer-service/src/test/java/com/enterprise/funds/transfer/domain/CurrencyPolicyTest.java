package com.enterprise.funds.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CurrencyPolicyTest {

    @Test
    void onlyUsdIsSupported() {
        assertThat(CurrencyPolicy.isSupported("USD")).isTrue();
        assertThat(CurrencyPolicy.isSupported("EUR")).isFalse();
        assertThat(CurrencyPolicy.isSupported(null)).isFalse();
    }

    @Test
    void usdAllowsTwoDecimalPlacesAndTolerantTrailingZeros() {
        assertThatCode(() -> CurrencyPolicy.requireScale("USD", new BigDecimal("250.00"))).doesNotThrowAnyException();
        assertThatCode(() -> CurrencyPolicy.requireScale("USD", new BigDecimal("250.0000"))).doesNotThrowAnyException();
        assertThatCode(() -> CurrencyPolicy.requireScale("USD", new BigDecimal("250"))).doesNotThrowAnyException();
        assertThatCode(() -> CurrencyPolicy.requireScale("USD", new BigDecimal("1E+3"))).doesNotThrowAnyException();
    }

    @Test
    void moreThanTwoSignificantDecimalsIsABadRequest() {
        assertThatThrownBy(() -> CurrencyPolicy.requireScale("USD", new BigDecimal("250.001")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(e.details()).extracting(ApiException.Detail::field).containsExactly("amount");
                });
    }

    @Test
    void formatsAtMinorUnitScale() {
        assertThat(CurrencyPolicy.format("USD", new BigDecimal("250.0000"))).isEqualTo("250.00");
        assertThat(CurrencyPolicy.format("USD", new BigDecimal("0.5"))).isEqualTo("0.50");
    }

    @Test
    void formattingNeverSilentlyRoundsMoney() {
        assertThatThrownBy(() -> CurrencyPolicy.format("USD", new BigDecimal("250.005"))).isInstanceOf(ArithmeticException.class);
    }
}
