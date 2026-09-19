package com.enterprise.funds.transfer.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Cross-field rules from the design (docs/milestone-1-contract.md section 4c). Failing here stops the
 * service at startup rather than letting a bad combination weaken the idempotency guarantee.
 */
@Component
public class FundsPropertiesValidator implements InitializingBean {

    private final FundsProperties props;

    public FundsPropertiesValidator(FundsProperties props) {
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        var idem = props.idempotency();
        if (idem.tombstoneRetention().compareTo(idem.replayRetention()) < 0) {
            throw new IllegalStateException(
                    "funds.idempotency.tombstone-retention must be >= replay-retention, otherwise a key could "
                            + "be forgotten while its response is still replayable");
        }
        if (idem.lease().compareTo(props.requestDeadline()) <= 0) {
            throw new IllegalStateException(
                    "funds.idempotency.lease must exceed funds.request-deadline, otherwise live requests are "
                            + "routinely taken over");
        }
        var risk = props.risk();
        if (risk.rejectThreshold() != null && risk.rejectThreshold().compareTo(risk.reviewThreshold()) < 0) {
            throw new IllegalStateException("funds.risk.reject-threshold must be >= review-threshold");
        }
    }
}
