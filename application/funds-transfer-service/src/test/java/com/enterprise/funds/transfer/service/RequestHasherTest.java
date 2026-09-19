package com.enterprise.funds.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    private final RequestHasher hasher = new RequestHasher();

    private byte[] hash(String amount, String reference) {
        return hasher.hash("ACC001", "ACC002", new BigDecimal(amount), "USD", reference);
    }

    @Test
    void identicalRequestsHashTheSame() {
        assertThat(hash("250.00", "Invoice")).isEqualTo(hash("250.00", "Invoice"));
    }

    @Test
    void hashIsSha256() {
        assertThat(hash("1", null)).hasSize(32);
    }

    @Test
    void amountsAreComparedByDecimalValue() {
        assertThat(hash("250.00", null)).isEqualTo(hash("250.0", null));
        assertThat(hash("250.00", null)).isEqualTo(hash("250", null));
        assertThat(hash("0.00", null)).isEqualTo(hash("0", null));
    }

    @Test
    void differentAmountsDiffer() {
        assertThat(hash("250.00", null)).isNotEqualTo(hash("250.01", null));
    }

    @Test
    void absentReferenceEqualsNullButEmptyStringDoesNot() {
        assertThat(hash("1", null)).isEqualTo(hash("1", null));
        assertThat(hash("1", null)).isNotEqualTo(hash("1", ""));
    }

    @Test
    void otherStringsAreExactIncludingWhitespaceAndCase() {
        assertThat(hash("1", "Invoice")).isNotEqualTo(hash("1", "Invoice "));
        assertThat(hash("1", "Invoice")).isNotEqualTo(hash("1", "invoice"));
    }

    @Test
    void everyFieldMatters() {
        byte[] base = hasher.hash("ACC001", "ACC002", BigDecimal.TEN, "USD", "x");
        assertThat(base).isNotEqualTo(hasher.hash("ACC009", "ACC002", BigDecimal.TEN, "USD", "x"));
        assertThat(base).isNotEqualTo(hasher.hash("ACC001", "ACC009", BigDecimal.TEN, "USD", "x"));
        assertThat(base).isNotEqualTo(hasher.hash("ACC001", "ACC002", BigDecimal.TEN, "EUR", "x"));
    }

    @Test
    void swappingSourceAndDestinationIsADifferentRequest() {
        assertThat(hasher.hash("A", "B", BigDecimal.ONE, "USD", null))
                .isNotEqualTo(hasher.hash("B", "A", BigDecimal.ONE, "USD", null));
    }
}
