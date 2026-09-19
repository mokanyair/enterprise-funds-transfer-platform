package com.enterprise.funds.transfer.it;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.Role;
import com.enterprise.funds.transfer.persistence.Ids;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Creates test data as the schema OWNER (the runtime user cannot insert customers or accounts, by design).
 * Every fixture uses unique ids and account numbers so tests never collide. AUDIT_EVENTS is immutable, so this
 * data is not removed: run the ITs only against a disposable database.
 */
final class OracleFixture {

    final JdbcTemplate owner;

    OracleFixture() {
        var ds = new DriverManagerDataSource(env("FUNDS_DB_URL"), env("FUNDS_IT_OWNER_USER"), env("FUNDS_IT_OWNER_PASSWORD"));
        ds.setConnectionProperties(new java.util.Properties());
        this.owner = new JdbcTemplate(ds);
        this.owner.execute("ALTER SESSION SET CURRENT_SCHEMA = " + System.getenv().getOrDefault("FUNDS_DB_SCHEMA", "FUNDS_OWNER"));
    }

    static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Set " + name + " to run the Oracle integration tests");
        }
        return v;
    }

    record Account(UUID id, String number, UUID customerId) {}

    UUID customer() {
        UUID id = Ids.newId();
        owner.update("INSERT INTO CUSTOMERS (CUSTOMER_ID, NAME, STATUS) VALUES (?, ?, 'ACTIVE')", Ids.toBytes(id), "IT " + id);
        return id;
    }

    Actor customerUser(UUID customerId) {
        UUID id = Ids.newId();
        owner.update("INSERT INTO APP_USERS (USER_ID, CUSTOMER_ID, IDP_SUBJECT, ROLE, STATUS) VALUES (?, ?, ?, 'CUSTOMER', 'ACTIVE')",
                Ids.toBytes(id), Ids.toBytes(customerId), "it-" + id);
        return new Actor(id, Role.CUSTOMER, customerId);
    }

    Actor operator() {
        UUID id = Ids.newId();
        owner.update("INSERT INTO APP_USERS (USER_ID, CUSTOMER_ID, IDP_SUBJECT, ROLE, STATUS) VALUES (?, NULL, ?, 'OPERATOR', 'ACTIVE')",
                Ids.toBytes(id), "it-op-" + id);
        return new Actor(id, Role.OPERATOR, null);
    }

    Account account(UUID customerId, String balance) {
        UUID id = Ids.newId();
        String number = "T" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 50), 36).toUpperCase();
        owner.update("INSERT INTO ACCOUNTS (ACCOUNT_ID, ACCOUNT_NUMBER, CUSTOMER_ID, CURRENCY, STATUS) VALUES (?, ?, ?, 'USD', 'ACTIVE')",
                Ids.toBytes(id), number, Ids.toBytes(customerId));
        owner.update("INSERT INTO ACCOUNT_BALANCES (ACCOUNT_ID, AVAILABLE_BALANCE, LEDGER_BALANCE) VALUES (?, ?, ?)",
                Ids.toBytes(id), new BigDecimal(balance), new BigDecimal(balance));
        return new Account(id, number, customerId);
    }

    void accountLimit(Account account, String period, String amount) {
        owner.update("INSERT INTO TRANSFER_LIMITS (LIMIT_ID, ACCOUNT_ID, PERIOD, AMOUNT, CURRENCY, EFFECTIVE_FROM) "
                + "VALUES (?, ?, ?, ?, 'USD', SYSTIMESTAMP - INTERVAL '1' DAY)",
                Ids.toBytes(Ids.newId()), Ids.toBytes(account.id()), period, new BigDecimal(amount));
    }

    void customerLimit(UUID customerId, String period, String amount) {
        owner.update("INSERT INTO TRANSFER_LIMITS (LIMIT_ID, CUSTOMER_ID, PERIOD, AMOUNT, CURRENCY, EFFECTIVE_FROM) "
                + "VALUES (?, ?, ?, ?, 'USD', SYSTIMESTAMP - INTERVAL '1' DAY)",
                Ids.toBytes(Ids.newId()), Ids.toBytes(customerId), period, new BigDecimal(amount));
    }

    BigDecimal balance(Account a) {
        return owner.queryForObject("SELECT LEDGER_BALANCE FROM ACCOUNT_BALANCES WHERE ACCOUNT_ID = ?", BigDecimal.class, Ids.toBytes(a.id()));
    }

    BigDecimal availableBalance(Account a) {
        return owner.queryForObject("SELECT AVAILABLE_BALANCE FROM ACCOUNT_BALANCES WHERE ACCOUNT_ID = ?", BigDecimal.class, Ids.toBytes(a.id()));
    }

    int ledgerEntriesFor(Account a) {
        return owner.queryForObject("SELECT COUNT(*) FROM LEDGER_ENTRIES WHERE ACCOUNT_ID = ?", Integer.class, Ids.toBytes(a.id()));
    }

    int ledgerEntriesForTransfer(UUID transferId) {
        return owner.queryForObject("SELECT COUNT(*) FROM LEDGER_ENTRIES WHERE TRANSFER_ID = ?", Integer.class, Ids.toBytes(transferId));
    }

    BigDecimal totalDebits(Account... accounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (Account a : accounts) {
            total = total.add(owner.queryForObject("SELECT NVL(SUM(AMOUNT),0) FROM LEDGER_ENTRIES WHERE ACCOUNT_ID = ? AND SIDE = 'DEBIT'",
                    BigDecimal.class, Ids.toBytes(a.id())));
        }
        return total;
    }

    int transfersFrom(Account a) {
        return owner.queryForObject("SELECT COUNT(*) FROM TRANSFERS WHERE SOURCE_ACCOUNT_ID = ?", Integer.class, Ids.toBytes(a.id()));
    }
}
