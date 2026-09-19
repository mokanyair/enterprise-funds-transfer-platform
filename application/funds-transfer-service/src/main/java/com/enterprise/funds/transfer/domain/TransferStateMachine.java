package com.enterprise.funds.transfer.domain;

import static com.enterprise.funds.transfer.domain.TransferStatus.*;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The legal transfer transitions, exactly as in docs/milestone-1-contract.md section 3. Any other
 * transition is a defect and is refused. Kafka publication is an outbox state, not a transfer state.
 */
public final class TransferStateMachine {

    private static final Map<TransferStatus, Set<TransferStatus>> ALLOWED = new EnumMap<>(TransferStatus.class);

    static {
        ALLOWED.put(RECEIVED, EnumSet.of(VALIDATING, CANCELLED));
        ALLOWED.put(VALIDATING, EnumSet.of(PENDING_REVIEW, PROCESSING, REJECTED, CANCELLED));
        ALLOWED.put(PENDING_REVIEW, EnumSet.of(PROCESSING, REJECTED, CANCELLED));
        ALLOWED.put(PROCESSING, EnumSet.of(COMPLETED, FAILED));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(TransferStatus.class));
        ALLOWED.put(REJECTED, EnumSet.noneOf(TransferStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(TransferStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(TransferStatus.class));
    }

    private TransferStateMachine() {}

    public static boolean canTransition(TransferStatus from, TransferStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /** @throws IllegalStateException on an illegal transition (a programming error, never a client error) */
    public static TransferStatus require(TransferStatus from, TransferStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal transfer transition " + from + " -> " + to);
        }
        return to;
    }

    /** Walks a path of states, checking every step. Returns the final state. */
    public static TransferStatus walk(TransferStatus start, TransferStatus... path) {
        TransferStatus current = start;
        for (TransferStatus next : path) {
            current = require(current, next);
        }
        return current;
    }
}
