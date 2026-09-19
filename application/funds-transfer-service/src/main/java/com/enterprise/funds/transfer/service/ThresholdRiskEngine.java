package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.domain.RiskDecision;
import com.enterprise.funds.transfer.domain.RiskDecision.Outcome;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Default engine: amount thresholds only. Amounts at or above the review threshold go to manual review,
 * at or above the (optional) reject threshold are refused. A real engine replaces this bean.
 */
@Component
public class ThresholdRiskEngine implements RiskEngine {

    private final BigDecimal reviewThreshold;
    private final BigDecimal rejectThreshold;

    public ThresholdRiskEngine(FundsProperties props) {
        this.reviewThreshold = props.risk().reviewThreshold();
        this.rejectThreshold = props.risk().rejectThreshold();
    }

    @Override
    public RiskDecision assess(BigDecimal amount, String currency) {
        if (rejectThreshold != null && amount.compareTo(rejectThreshold) >= 0) {
            return new RiskDecision(Outcome.REJECT, "amount at or above reject threshold", BigDecimal.valueOf(100));
        }
        if (amount.compareTo(reviewThreshold) >= 0) {
            return new RiskDecision(Outcome.REVIEW, "amount at or above review threshold", BigDecimal.valueOf(60));
        }
        return new RiskDecision(Outcome.APPROVE, "within thresholds", BigDecimal.ZERO);
    }
}
