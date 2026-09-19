package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.persistence.AccountRepository;
import com.enterprise.funds.transfer.persistence.CustomerRepository;
import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.persistence.LedgerRepository;
import com.enterprise.funds.transfer.persistence.Rows.AccountRow;
import com.enterprise.funds.transfer.persistence.Rows.BalanceRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The one place money moves. Callers own the surrounding transaction and the transfer row; this class owns the
 * lock protocol (docs/milestone-2-erd.md sections 10 and 11):
 *
 * <pre>
 *   idempotency row (caller) -> source customer row -> balance rows in ascending ACCOUNT_ID
 *   then, under those locks: limits, then funds, then balances + two ledger entries
 * </pre>
 *
 * Used by both the direct path (POST /transfers) and the post-review path, so the same checks apply to both.
 */
@Service
public class PostingService {

    /** Balances read under lock. Valid only for the transaction that produced them. */
    public record Locked(BalanceRow source, BalanceRow destination) {}

    /** Unsigned byte order, matching how Oracle orders RAW(16). Any consistent order avoids deadlock. */
    private static final Comparator<java.util.UUID> ACCOUNT_ORDER =
            (a, b) -> Arrays.compareUnsigned(Ids.toBytes(a), Ids.toBytes(b));

    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final LimitChecker limits;

    public PostingService(CustomerRepository customers, AccountRepository accounts, LedgerRepository ledger,
                          LimitChecker limits) {
        this.customers = customers;
        this.accounts = accounts;
        this.ledger = ledger;
        this.limits = limits;
    }

    /** Takes the customer lock, then both balance locks in ascending account order. */
    public Locked lock(AccountRow source, AccountRow destination) {
        customers.lock(source.customerId());
        List<java.util.UUID> order = List.of(source.id(), destination.id()).stream().sorted(ACCOUNT_ORDER).toList();
        BalanceRow first = accounts.lockBalance(order.get(0));
        BalanceRow second = accounts.lockBalance(order.get(1));
        return source.id().equals(order.get(0)) ? new Locked(first, second) : new Locked(second, first);
    }

    /** The authoritative checks, in contract order: limits (LIMIT_EXCEEDED) then funds (INSUFFICIENT_FUNDS). */
    public void enforce(Locked locked, AccountRow source, BigDecimal amount, OffsetDateTime now) {
        limits.requireWithinLimits(source, amount, now);
        if (locked.source().available().compareTo(amount) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS);
        }
    }

    /**
     * Moves the money: both balance columns, and exactly two balanced ledger entries (DEBIT at position 1,
     * CREDIT at position 2). The transfer row must already exist (FK). Balancing is guaranteed here by
     * construction and independently checked by reconciliation.
     */
    public void apply(Locked locked, java.util.UUID transferId, AccountRow source, AccountRow destination,
                      BigDecimal amount, OffsetDateTime now) {
        BigDecimal sourceAfter = locked.source().ledger().subtract(amount);
        BigDecimal destinationAfter = locked.destination().ledger().add(amount);
        accounts.updateBalance(source.id(), sourceAfter, locked.source().version(), now);
        accounts.updateBalance(destination.id(), destinationAfter, locked.destination().version(), now);
        ledger.insert(Ids.newId(), transferId, source.id(), "DEBIT", amount, 1, sourceAfter, now);
        ledger.insert(Ids.newId(), transferId, destination.id(), "CREDIT", amount, 2, destinationAfter, now);
    }
}
