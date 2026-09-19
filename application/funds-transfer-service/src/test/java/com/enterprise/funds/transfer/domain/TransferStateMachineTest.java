package com.enterprise.funds.transfer.domain;

import static com.enterprise.funds.transfer.domain.TransferStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransferStateMachineTest {

    /** The table from docs/milestone-1-contract.md section 3, written out independently of the implementation. */
    private static final Map<TransferStatus, Set<TransferStatus>> CONTRACT = Map.of(
            RECEIVED, EnumSet.of(VALIDATING, CANCELLED),
            VALIDATING, EnumSet.of(PENDING_REVIEW, PROCESSING, REJECTED, CANCELLED),
            PENDING_REVIEW, EnumSet.of(PROCESSING, REJECTED, CANCELLED),
            PROCESSING, EnumSet.of(COMPLETED, FAILED),
            COMPLETED, EnumSet.noneOf(TransferStatus.class),
            REJECTED, EnumSet.noneOf(TransferStatus.class),
            FAILED, EnumSet.noneOf(TransferStatus.class),
            CANCELLED, EnumSet.noneOf(TransferStatus.class));

    @Test
    void everyFromToPairMatchesTheContract() {
        int checked = 0;
        for (TransferStatus from : TransferStatus.values()) {
            for (TransferStatus to : TransferStatus.values()) {
                boolean expected = CONTRACT.get(from).contains(to);
                assertThat(TransferStateMachine.canTransition(from, to))
                        .as("%s -> %s", from, to).isEqualTo(expected);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(64);
    }

    @Test
    void illegalTransitionIsRefused() {
        assertThatThrownBy(() -> TransferStateMachine.require(COMPLETED, CANCELLED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("COMPLETED -> CANCELLED");
    }

    @Test
    void aPostedTransferIsNeverCancelledInPlace() {
        assertThat(COMPLETED.isCancellable()).isFalse();
        assertThat(PROCESSING.isCancellable()).isFalse();
    }

    @Test
    void onlyUnpostedStatesAreCancellable() {
        assertThat(EnumSet.allOf(TransferStatus.class).stream().filter(TransferStatus::isCancellable))
                .containsExactlyInAnyOrder(RECEIVED, VALIDATING, PENDING_REVIEW);
    }

    @Test
    void terminalStatesHaveNoExit() {
        for (TransferStatus s : TransferStatus.values()) {
            assertThat(s.isTerminal()).isEqualTo(CONTRACT.get(s).isEmpty());
        }
    }

    @Test
    void walkChecksEveryStep() {
        assertThat(TransferStateMachine.walk(RECEIVED, VALIDATING, PROCESSING, COMPLETED)).isEqualTo(COMPLETED);
        assertThatThrownBy(() -> TransferStateMachine.walk(RECEIVED, PROCESSING))
                .isInstanceOf(IllegalStateException.class);
    }
}
