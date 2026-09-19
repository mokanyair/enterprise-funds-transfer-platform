package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.persistence.LedgerRepository;
import com.enterprise.funds.transfer.persistence.LimitRepository;
import com.enterprise.funds.transfer.persistence.Rows.AccountRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

@Service
public class LimitChecker {

    private final LimitRepository limits;
    private final LedgerRepository ledger;

    public LimitChecker(LimitRepository limits, LedgerRepository ledger) {
        this.limits = limits;
        this.ledger = ledger;
    }

    /**
     * Throws LIMIT_EXCEEDED when any applicable limit would be breached. Called twice: once as an advisory
     * pre-check without locks, and again as the authoritative check under the posting locks. Which limit
     * tripped is deliberately not revealed.
     */
    public void requireWithinLimits(AccountRow source, BigDecimal amount, OffsetDateTime now) {
        var applicable = limits.findApplicable(source.id(), source.customerId(), source.currency(), now);
        var violated = LimitEvaluator.firstViolation(applicable, amount, now, (limit, from) ->
                limit.accountId() != null
                        ? ledger.debitsForAccount(limit.accountId(), from)
                        : ledger.debitsForCustomer(limit.customerId(), from));
        if (violated.isPresent()) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED);
        }
    }
}
