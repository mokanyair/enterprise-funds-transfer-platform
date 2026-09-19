package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.domain.TransferStatus;
import com.enterprise.funds.transfer.persistence.Rows.TransferView;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private static final String VIEW_SELECT = """
            SELECT t.TRANSFER_ID, t.SOURCE_ACCOUNT_ID, s.ACCOUNT_NUMBER AS S_NUM, s.CUSTOMER_ID AS S_CUST,
                   t.DESTINATION_ACCOUNT_ID, d.ACCOUNT_NUMBER AS D_NUM, d.CUSTOMER_ID AS D_CUST,
                   t.AMOUNT, t.CURRENCY, t.STATUS, t.REFERENCE, t.CREATED_BY_USER_ID, t.REVERSAL_OF,
                   t.CREATED_AT, t.UPDATED_AT
              FROM TRANSFERS t
              JOIN ACCOUNTS s ON s.ACCOUNT_ID = t.SOURCE_ACCOUNT_ID
              JOIN ACCOUNTS d ON d.ACCOUNT_ID = t.DESTINATION_ACCOUNT_ID
            """;

    private static final RowMapper<TransferView> VIEW = (rs, i) -> new TransferView(
            JdbcSupport.uuid(rs, "TRANSFER_ID"),
            JdbcSupport.uuid(rs, "SOURCE_ACCOUNT_ID"), rs.getString("S_NUM"), JdbcSupport.uuid(rs, "S_CUST"),
            JdbcSupport.uuid(rs, "DESTINATION_ACCOUNT_ID"), rs.getString("D_NUM"), JdbcSupport.uuid(rs, "D_CUST"),
            rs.getBigDecimal("AMOUNT"), rs.getString("CURRENCY"), TransferStatus.valueOf(rs.getString("STATUS")),
            rs.getString("REFERENCE"), JdbcSupport.uuid(rs, "CREATED_BY_USER_ID"), JdbcSupport.uuid(rs, "REVERSAL_OF"),
            JdbcSupport.time(rs, "CREATED_AT"), JdbcSupport.time(rs, "UPDATED_AT"));

    private final NamedParameterJdbcTemplate jdbc;
    private final int lockWait;

    public TransferRepository(NamedParameterJdbcTemplate jdbc, FundsProperties props) {
        this.jdbc = jdbc;
        this.lockWait = props.db().lockWaitSeconds();
    }

    public void insert(UUID id, UUID sourceId, UUID destinationId, BigDecimal amount, String currency,
                       TransferStatus status, String reference, UUID createdBy, UUID reversalOf, OffsetDateTime now) {
        MapSqlParameterSource p = JdbcSupport.params();
        JdbcSupport.id(p, "id", id);
        JdbcSupport.id(p, "src", sourceId);
        JdbcSupport.id(p, "dst", destinationId);
        JdbcSupport.id(p, "by", createdBy);
        JdbcSupport.id(p, "rev", reversalOf);
        p.addValue("amount", amount).addValue("currency", currency).addValue("status", status.name())
                .addValue("ref", reference).addValue("now", now);
        jdbc.update("""
                INSERT INTO TRANSFERS (TRANSFER_ID, SOURCE_ACCOUNT_ID, DESTINATION_ACCOUNT_ID, AMOUNT, CURRENCY,
                                       STATUS, REFERENCE, CREATED_BY_USER_ID, REVERSAL_OF, CREATED_AT, UPDATED_AT)
                VALUES (:id, :src, :dst, :amount, :currency, :status, :ref, :by, :rev, :now, :now)
                """, p);
    }

    public Optional<TransferView> findView(UUID id) {
        List<TransferView> rows = jdbc.query(VIEW_SELECT + " WHERE t.TRANSFER_ID = :id",
                JdbcSupport.id(JdbcSupport.params(), "id", id), VIEW);
        return rows.stream().findFirst();
    }

    /** Locks only the transfer row (not the joined accounts). Throws CannotAcquireLockException on timeout. */
    public Optional<TransferView> lockView(UUID id) {
        List<TransferView> rows = jdbc.query(
                VIEW_SELECT + " WHERE t.TRANSFER_ID = :id FOR UPDATE OF t.TRANSFER_ID WAIT " + lockWait,
                JdbcSupport.id(JdbcSupport.params(), "id", id), VIEW);
        return rows.stream().findFirst();
    }

    /** Compare-and-set on status, so a concurrent change surfaces as an error instead of a lost update. */
    public void updateStatus(UUID id, TransferStatus from, TransferStatus to, OffsetDateTime now) {
        int updated = jdbc.update(
                "UPDATE TRANSFERS SET STATUS = :to, UPDATED_AT = :now WHERE TRANSFER_ID = :id AND STATUS = :from",
                JdbcSupport.id(JdbcSupport.params(), "id", id)
                        .addValue("to", to.name()).addValue("from", from.name()).addValue("now", now));
        if (updated != 1) {
            throw new IllegalStateException("Transfer " + id + " was not in state " + from);
        }
    }

    public List<TransferView> listForAccount(UUID accountId, TransferStatus statusFilter, int offset, int size) {
        return jdbc.query(VIEW_SELECT + accountFilter(statusFilter)
                        + " ORDER BY t.CREATED_AT DESC, t.TRANSFER_ID DESC OFFSET :off ROWS FETCH NEXT :size ROWS ONLY",
                accountParams(accountId, statusFilter).addValue("off", offset).addValue("size", size), VIEW);
    }

    public long countForAccount(UUID accountId, TransferStatus statusFilter) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM TRANSFERS t" + accountFilter(statusFilter),
                accountParams(accountId, statusFilter), Long.class);
        return n == null ? 0 : n;
    }

    private static String accountFilter(TransferStatus statusFilter) {
        return " WHERE (t.SOURCE_ACCOUNT_ID = :acc OR t.DESTINATION_ACCOUNT_ID = :acc)"
                + (statusFilter == null ? "" : " AND t.STATUS = :status");
    }

    private static MapSqlParameterSource accountParams(UUID accountId, TransferStatus statusFilter) {
        MapSqlParameterSource p = JdbcSupport.id(JdbcSupport.params(), "acc", accountId);
        if (statusFilter != null) {
            p.addValue("status", statusFilter.name());
        }
        return p;
    }
}
