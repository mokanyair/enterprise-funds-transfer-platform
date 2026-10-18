package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.persistence.Rows.BeneficiaryRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * BENEFICIARIES (V7). Every read is scoped by CUSTOMER_ID, so another customer's row is never loaded. Rows are
 * never deleted: TRG_BENEF_IMMUTABLE and the missing DELETE grant allow only ACTIVE -> DELETED.
 */
@Repository
public class BeneficiaryRepository {

    private static final RowMapper<BeneficiaryRow> ROW = (rs, i) -> new BeneficiaryRow(
            JdbcSupport.uuid(rs, "BENEFICIARY_ID"), JdbcSupport.uuid(rs, "CUSTOMER_ID"),
            JdbcSupport.uuid(rs, "ACCOUNT_ID"), rs.getString("ACCOUNT_NUMBER"), rs.getString("NICKNAME"),
            rs.getString("STATUS"), JdbcSupport.time(rs, "CREATED_AT"));

    private static final String SELECT = """
            SELECT b.BENEFICIARY_ID, b.CUSTOMER_ID, b.ACCOUNT_ID, a.ACCOUNT_NUMBER, b.NICKNAME, b.STATUS, b.CREATED_AT
              FROM BENEFICIARIES b JOIN ACCOUNTS a ON a.ACCOUNT_ID = b.ACCOUNT_ID
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BeneficiaryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Backs GET /beneficiaries: nickname case-insensitively, then id, as the contract orders it. */
    public List<BeneficiaryRow> listActive(UUID customerId) {
        return jdbc.query(SELECT + " WHERE b.CUSTOMER_ID = :c AND b.STATUS = 'ACTIVE' "
                        + "ORDER BY LOWER(b.NICKNAME), b.BENEFICIARY_ID",
                JdbcSupport.id(JdbcSupport.params(), "c", customerId), ROW);
    }

    /** Any status, so a repeated delete can be told apart from an unknown id. */
    public Optional<BeneficiaryRow> find(UUID customerId, UUID beneficiaryId) {
        var p = JdbcSupport.id(JdbcSupport.params(), "c", customerId);
        JdbcSupport.id(p, "id", beneficiaryId);
        return jdbc.query(SELECT + " WHERE b.CUSTOMER_ID = :c AND b.BENEFICIARY_ID = :id", p, ROW)
                .stream().findFirst();
    }

    public Optional<BeneficiaryRow> findActiveByAccount(UUID customerId, UUID accountId) {
        var p = JdbcSupport.id(JdbcSupport.params(), "c", customerId);
        JdbcSupport.id(p, "a", accountId);
        return jdbc.query(SELECT + " WHERE b.CUSTOMER_ID = :c AND b.ACCOUNT_ID = :a AND b.STATUS = 'ACTIVE'", p, ROW)
                .stream().findFirst();
    }

    public int countActive(UUID customerId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM BENEFICIARIES WHERE CUSTOMER_ID = :c AND STATUS = 'ACTIVE'",
                JdbcSupport.id(JdbcSupport.params(), "c", customerId), Integer.class);
        return n == null ? 0 : n;
    }

    /** Adds since a point in time, deleted ones included: a delete must not refund the daily add cap (B5). */
    public int countCreatedSince(UUID customerId, OffsetDateTime since) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM BENEFICIARIES WHERE CUSTOMER_ID = :c AND CREATED_AT >= :since",
                JdbcSupport.id(JdbcSupport.params(), "c", customerId).addValue("since", since), Integer.class);
        return n == null ? 0 : n;
    }

    /** Throws DuplicateKeyException (UX_BENEF_ACTIVE) if the pair is already active. */
    public void insert(UUID id, UUID customerId, UUID accountId, String nickname, UUID createdBy, OffsetDateTime now) {
        var p = JdbcSupport.id(JdbcSupport.params(), "id", id);
        JdbcSupport.id(p, "c", customerId);
        JdbcSupport.id(p, "a", accountId);
        JdbcSupport.id(p, "u", createdBy);
        p.addValue("nick", nickname).addValue("now", now);
        jdbc.update("""
                INSERT INTO BENEFICIARIES (BENEFICIARY_ID, CUSTOMER_ID, ACCOUNT_ID, NICKNAME, STATUS,
                                           CREATED_BY_USER_ID, CREATED_AT)
                VALUES (:id, :c, :a, :nick, 'ACTIVE', :u, :now)
                """, p);
    }

    /** Soft delete. Returns false when the row was no longer ACTIVE (a concurrent delete won). */
    public boolean markDeleted(UUID customerId, UUID beneficiaryId, UUID deletedBy, OffsetDateTime now) {
        var p = JdbcSupport.id(JdbcSupport.params(), "c", customerId);
        JdbcSupport.id(p, "id", beneficiaryId);
        JdbcSupport.id(p, "u", deletedBy);
        p.addValue("now", now);
        return jdbc.update("""
                UPDATE BENEFICIARIES SET STATUS = 'DELETED', DELETED_AT = :now, DELETED_BY_USER_ID = :u
                 WHERE BENEFICIARY_ID = :id AND CUSTOMER_ID = :c AND STATUS = 'ACTIVE'
                """, p) == 1;
    }
}
