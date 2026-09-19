package com.enterprise.funds.transfer.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only. The runtime identity holds SELECT and INSERT on LEDGER_ENTRIES, nothing else. */
@Repository
public class LedgerRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LedgerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID entryId, UUID transferId, UUID accountId, String side, BigDecimal amount,
                       int position, BigDecimal balanceAfter, OffsetDateTime postedAt) {
        var p = JdbcSupport.params();
        JdbcSupport.id(p, "id", entryId);
        JdbcSupport.id(p, "transfer", transferId);
        JdbcSupport.id(p, "account", accountId);
        p.addValue("side", side).addValue("amount", amount).addValue("pos", position)
                .addValue("after", balanceAfter).addValue("at", postedAt);
        jdbc.update("""
                INSERT INTO LEDGER_ENTRIES (ENTRY_ID, TRANSFER_ID, ACCOUNT_ID, SIDE, AMOUNT, ENTRY_POSITION,
                                            BALANCE_AFTER, POSTED_AT)
                VALUES (:id, :transfer, :account, :side, :amount, :pos, :after, :at)
                """, p);
    }

    /**
     * Cumulative debits from one account since the window start, excluding reversals (design L4: reversals
     * neither count nor restore usage). Uses IX_LEDGER_ACCOUNT. Must run after the posting locks are held.
     */
    public BigDecimal debitsForAccount(UUID accountId, OffsetDateTime from) {
        return sum("""
                SELECT NVL(SUM(e.AMOUNT), 0) FROM LEDGER_ENTRIES e JOIN TRANSFERS t ON t.TRANSFER_ID = e.TRANSFER_ID
                 WHERE e.ACCOUNT_ID = :scope AND e.SIDE = 'DEBIT' AND e.POSTED_AT >= :from AND t.REVERSAL_OF IS NULL
                """, accountId, from);
    }

    /** Cumulative debits across all of a customer's accounts (design L8: own-account moves count). */
    public BigDecimal debitsForCustomer(UUID customerId, OffsetDateTime from) {
        return sum("""
                SELECT NVL(SUM(e.AMOUNT), 0) FROM LEDGER_ENTRIES e JOIN TRANSFERS t ON t.TRANSFER_ID = e.TRANSFER_ID
                 WHERE e.ACCOUNT_ID IN (SELECT ACCOUNT_ID FROM ACCOUNTS WHERE CUSTOMER_ID = :scope)
                   AND e.SIDE = 'DEBIT' AND e.POSTED_AT >= :from AND t.REVERSAL_OF IS NULL
                """, customerId, from);
    }

    private BigDecimal sum(String sql, UUID scope, OffsetDateTime from) {
        var p = JdbcSupport.id(JdbcSupport.params(), "scope", scope).addValue("from", from);
        BigDecimal total = jdbc.queryForObject(sql, p, BigDecimal.class);
        return total == null ? BigDecimal.ZERO : total;
    }
}
