package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.domain.RiskDecision;
import java.math.BigDecimal;

/** Pluggable risk decision. Results are stored internally and never exposed through the API. */
public interface RiskEngine {
    RiskDecision assess(BigDecimal amount, String currency);
}
