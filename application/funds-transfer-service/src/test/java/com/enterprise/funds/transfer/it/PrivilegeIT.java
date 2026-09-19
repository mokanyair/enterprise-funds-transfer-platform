package com.enterprise.funds.transfer.it;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.funds.transfer.persistence.Ids;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the runtime identity (the application's datasource) cannot do what design P8 and the briefing forbid:
 * DDL, DELETE, rewriting the ledger or audit trail, or reading other schemas.
 * Skipped unless FUNDS_IT_ENABLED=true. NOT RUN YET.
 */
@SpringBootTest(properties = {"funds.jobs.enabled=false", "FUNDS_JWT_ISSUER_URI=https://issuer.invalid/"})
@EnabledIfEnvironmentVariable(named = "FUNDS_IT_ENABLED", matches = "true")
class PrivilegeIT {

    @Autowired DataSource runtimeDataSource;
    @MockitoBean JwtDecoder jwtDecoder;

    private JdbcTemplate runtime() {
        return new JdbcTemplate(runtimeDataSource);
    }

    @Test
    void runtimeUserCannotCreateOrDropTables() {
        assertThatThrownBy(() -> runtime().execute("CREATE TABLE FUNDS_IT_SHOULD_NOT_EXIST (X NUMBER)")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> runtime().execute("DROP TABLE TRANSFERS")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> runtime().execute("TRUNCATE TABLE LEDGER_ENTRIES")).isInstanceOf(Exception.class);
    }

    @Test
    void runtimeUserCannotDeleteAnything() {
        for (String table : new String[] {"TRANSFERS", "LEDGER_ENTRIES", "AUDIT_EVENTS", "IDEMPOTENCY_RECORDS",
                "ACCOUNT_BALANCES", "OUTBOX_EVENTS", "CUSTOMERS"}) {
            assertThatThrownBy(() -> runtime().update("DELETE FROM " + table + " WHERE 1 = 0"))
                    .as("DELETE on %s must be refused", table).isInstanceOf(Exception.class);
        }
    }

    @Test
    void ledgerAndAuditAreAppendOnly() {
        assertThatThrownBy(() -> runtime().update("UPDATE LEDGER_ENTRIES SET AMOUNT = AMOUNT WHERE 1 = 0")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> runtime().update("UPDATE AUDIT_EVENTS SET OUTCOME = OUTCOME WHERE 1 = 0")).isInstanceOf(Exception.class);
    }

    @Test
    void runtimeUserCannotCreateCustomersOrAccounts() {
        assertThatThrownBy(() -> runtime().update("INSERT INTO CUSTOMERS (CUSTOMER_ID, NAME, STATUS) VALUES (?, 'x', 'ACTIVE')",
                Ids.toBytes(UUID.randomUUID()))).isInstanceOf(Exception.class);
    }

    @Test
    void runtimeUserCannotReadOtherSchemas() {
        assertThatThrownBy(() -> runtime().queryForList("SELECT * FROM SYS.USER$ FETCH FIRST 1 ROWS ONLY")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> runtime().queryForList("SELECT * FROM DBA_USERS FETCH FIRST 1 ROWS ONLY")).isInstanceOf(Exception.class);
    }
}
