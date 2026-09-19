package com.enterprise.funds.transfer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void idsAreVersion7WithRfcVariant() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheTimestampAndSortsByTime() {
        UUID earlier = Ids.uuidV7(1_700_000_000_000L);
        UUID later = Ids.uuidV7(1_700_000_000_001L);
        assertThat(earlier.getMostSignificantBits() >>> 16).isEqualTo(1_700_000_000_000L);
        assertThat(java.util.Arrays.compareUnsigned(Ids.toBytes(earlier), Ids.toBytes(later))).isNegative();
    }

    @Test
    void idsAreUnique() {
        assertThat(java.util.stream.Stream.generate(Ids::newId).limit(10_000).distinct().count()).isEqualTo(10_000);
    }

    @Test
    void roundTripsThroughRaw16() {
        UUID id = Ids.newId();
        byte[] raw = Ids.toBytes(id);
        assertThat(raw).hasSize(16);
        assertThat(Ids.fromBytes(raw)).isEqualTo(id);
        assertThat(Ids.fromBytes(null)).isNull();
    }

    @Test
    void rejectsWrongLength() {
        assertThatThrownBy(() -> Ids.fromBytes(new byte[8])).isInstanceOf(IllegalArgumentException.class);
    }
}
