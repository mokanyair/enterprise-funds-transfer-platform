package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.persistence.Rows.AccountRow;
import com.enterprise.funds.transfer.persistence.Rows.BalanceRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final RowMapper<AccountRow> ACCOUNT = (rs, i) -> new AccountRow(
            JdbcSupport.uuid(rs, "ACCOUNT_ID"), rs.getString("ACCOUNT_NUMBER"),
            JdbcSupport.uuid(rs, "CUSTOMER_ID"), rs.getString("CURRENCY"), rs.getString("STATUS"));

    private static final RowMapper<BalanceRow> BALANCE = (rs, i) -> new BalanceRow(
            JdbcSupport.uuid(rs, "ACCOUNT_ID"), rs.getBigDecimal("AVAILABLE_BALANCE"),
            rs.getBigDecimal("LEDGER_BALANCE"), rs.getLong("VERSION"));

    private static final String ACCOUNT_COLUMNS = "ACCOUNT_ID, ACCOUNT_NUMBER, CUSTOMER_ID, CURRENCY, STATUS";
    private static final String BALANCE_COLUMNS = "ACCOUNT_ID, AVAILABLE_BALANCE, LEDGER_BALANCE, VERSION";

    private final NamedParameterJdbcTemplate jdbc;
    private final int lockWait;

    public AccountRepository(NamedParameterJdbcTemplate jdbc, FundsProperties props) {
        this.jdbc = jdbc;
        this.lockWait = props.db().lockWaitSeconds();
    }

    public Optional<AccountRow> findByNumber(String accountNumber) {
        List<AccountRow> rows = jdbc.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM ACCOUNTS WHERE ACCOUNT_NUMBER = :n",
                JdbcSupport.params().addValue("n", accountNumber), ACCOUNT);
        return rows.stream().findFirst();
    }

    public Optional<AccountRow> findById(UUID id) {
        List<AccountRow> rows = jdbc.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM ACCOUNTS WHERE ACCOUNT_ID = :id",
                JdbcSupport.id(JdbcSupport.params(), "id", id), ACCOUNT);
        return rows.stream().findFirst();
    }

    /** Unlocked read for the balance endpoint. */
    public Optional<BalanceRow> findBalance(UUID accountId) {
        List<BalanceRow> rows = jdbc.query(
                "SELECT " + BALANCE_COLUMNS + " FROM ACCOUNT_BALANCES WHERE ACCOUNT_ID = :id",
                JdbcSupport.id(JdbcSupport.params(), "id", accountId), BALANCE);
        return rows.stream().findFirst();
    }

    /**
     * Locks one balance row and returns its current value. Callers lock in ascending ACCOUNT_ID order so
     * transfers in opposite directions cannot deadlock. Throws CannotAcquireLockException on timeout.
     */
    public BalanceRow lockBalance(UUID accountId) {
        List<BalanceRow> rows = jdbc.query(
                "SELECT " + BALANCE_COLUMNS + " FROM ACCOUNT_BALANCES WHERE ACCOUNT_ID = :id FOR UPDATE WAIT " + lockWait,
                JdbcSupport.id(JdbcSupport.params(), "id", accountId), BALANCE);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Account has no balance row: " + accountId);
        }
        return rows.get(0);
    }

    /**
     * Writes the new balance. AVAILABLE and LEDGER are set together in one statement: V1 has no holds and
     * CK_ACCT_BAL_EQUAL requires them to be equal. Must run while the row is locked.
     */
    public void updateBalance(UUID accountId, BigDecimal newBalance, long expectedVersion, OffsetDateTime now) {
        int updated = jdbc.update(
                "UPDATE ACCOUNT_BALANCES SET AVAILABLE_BALANCE = :b, LEDGER_BALANCE = :b, "
                        + "VERSION = VERSION + 1, UPDATED_AT = :now WHERE ACCOUNT_ID = :id AND VERSION = :v",
                JdbcSupport.id(JdbcSupport.params(), "id", accountId)
                        .addValue("b", newBalance).addValue("now", now).addValue("v", expectedVersion));
        if (updated != 1) {
            throw new IllegalStateException("Balance changed under lock for " + accountId);
        }
    }
}
