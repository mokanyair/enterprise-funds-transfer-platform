package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.persistence.Rows.LimitRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LimitRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LimitRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Limits in force now that apply to this source account or to its customer. All of them must hold. */
    public List<LimitRow> findApplicable(UUID accountId, UUID customerId, String currency, OffsetDateTime now) {
        var p = JdbcSupport.params();
        JdbcSupport.id(p, "acc", accountId);
        JdbcSupport.id(p, "cust", customerId);
        p.addValue("cur", currency).addValue("now", now);
        return jdbc.query("""
                SELECT LIMIT_ID, CUSTOMER_ID, ACCOUNT_ID, PERIOD, AMOUNT FROM TRANSFER_LIMITS
                 WHERE (ACCOUNT_ID = :acc OR CUSTOMER_ID = :cust) AND CURRENCY = :cur
                   AND EFFECTIVE_FROM <= :now AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO > :now)
                """, p, (rs, i) -> new LimitRow(
                JdbcSupport.uuid(rs, "LIMIT_ID"), JdbcSupport.uuid(rs, "CUSTOMER_ID"),
                JdbcSupport.uuid(rs, "ACCOUNT_ID"), rs.getString("PERIOD"), rs.getBigDecimal("AMOUNT")));
    }
}
