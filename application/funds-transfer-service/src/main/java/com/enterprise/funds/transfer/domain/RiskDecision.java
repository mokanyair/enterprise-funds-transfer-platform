package com.enterprise.funds.transfer.domain;

import java.math.BigDecimal;

/** Internal only. Never exposed through the API (contract: no risk detail on the wire). */
public record RiskDecision(Outcome outcome, String reason, BigDecimal score) {
    public enum Outcome { APPROVE, REVIEW, REJECT }
}
