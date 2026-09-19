package com.enterprise.funds.transfer.domain;

public enum TransferStatus {
    RECEIVED, VALIDATING, PENDING_REVIEW, PROCESSING, COMPLETED, REJECTED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == FAILED || this == CANCELLED;
    }

    /** Only unposted transfers may be cancelled. PROCESSING is excluded: posting may be under way. */
    public boolean isCancellable() {
        return this == RECEIVED || this == VALIDATING || this == PENDING_REVIEW;
    }
}
