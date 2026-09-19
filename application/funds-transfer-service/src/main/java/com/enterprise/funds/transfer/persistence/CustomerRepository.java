package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.config.FundsProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final int lockWait;

    public CustomerRepository(NamedParameterJdbcTemplate jdbc, FundsProperties props) {
        this.jdbc = jdbc;
        this.lockWait = props.db().lockWaitSeconds();
    }

    /**
     * Serialises every posting for one customer, which is what makes customer-wide daily and monthly limits
     * safe (docs/milestone-2-erd.md section 10). Works with SELECT-only privilege on CUSTOMERS.
     * Throws CannotAcquireLockException when the wait expires (ORA-30006).
     */
    public void lock(UUID customerId) {
        List<Integer> rows = jdbc.query(
                "SELECT 1 FROM CUSTOMERS WHERE CUSTOMER_ID = :id FOR UPDATE WAIT " + lockWait,
                JdbcSupport.id(JdbcSupport.params(), "id", customerId), (rs, i) -> 1);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Customer disappeared: " + customerId);
        }
    }
}
