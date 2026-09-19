package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.persistence.Rows.IdempotencyRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private static final String COLUMNS = """
            ID, USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, STATUS, ATTEMPT, LEASE_EXPIRES_AT, TRANSFER_ID,
            RESPONSE_STATUS, RESPONSE_BODY, REPLAY_EXPIRES_AT, RESPONSE_PURGED_AT""";

    private static final RowMapper<IdempotencyRow> ROW = (rs, i) -> {
        int status = rs.getInt("RESPONSE_STATUS");
        Integer responseStatus = rs.wasNull() ? null : status;
        return new IdempotencyRow(
                JdbcSupport.uuid(rs, "ID"), JdbcSupport.uuid(rs, "USER_ID"), JdbcSupport.uuid(rs, "IDEMPOTENCY_KEY"),
                rs.getBytes("REQUEST_HASH"), rs.getString("STATUS"), rs.getInt("ATTEMPT"),
                JdbcSupport.time(rs, "LEASE_EXPIRES_AT"), JdbcSupport.uuid(rs, "TRANSFER_ID"), responseStatus,
                rs.getString("RESPONSE_BODY"), JdbcSupport.time(rs, "REPLAY_EXPIRES_AT"),
                JdbcSupport.time(rs, "RESPONSE_PURGED_AT"));
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final int lockWait;

    public JdbcIdempotencyRepository(NamedParameterJdbcTemplate jdbc, FundsProperties props) {
        this.jdbc = jdbc;
        this.lockWait = props.db().lockWaitSeconds();
    }

    @Override
    public boolean insertInProgress(UUID id, UUID userId, UUID key, byte[] requestHash,
                                    OffsetDateTime leaseExpiresAt, OffsetDateTime now) {
        MapSqlParameterSource p = JdbcSupport.params();
        JdbcSupport.id(p, "id", id);
        JdbcSupport.id(p, "user", userId);
        JdbcSupport.id(p, "key", key);
        p.addValue("hash", requestHash, java.sql.Types.BINARY).addValue("lease", leaseExpiresAt).addValue("now", now);
        try {
            jdbc.update("""
                    INSERT INTO IDEMPOTENCY_RECORDS (ID, USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, STATUS, ATTEMPT,
                                                     LEASE_EXPIRES_AT, CREATED_AT)
                    VALUES (:id, :user, :key, :hash, 'IN_PROGRESS', 1, :lease, :now)
                    """, p);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<IdempotencyRow> find(UUID userId, UUID key) {
        MapSqlParameterSource p = JdbcSupport.id(JdbcSupport.params(), "user", userId);
        JdbcSupport.id(p, "key", key);
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM IDEMPOTENCY_RECORDS WHERE USER_ID = :user AND IDEMPOTENCY_KEY = :key",
                p, ROW);
        return rows.stream().findFirst();
    }

    @Override
    public boolean takeover(UUID id, int seenAttempt, byte[] requestHash, OffsetDateTime now,
                            OffsetDateTime newLeaseExpiresAt) {
        MapSqlParameterSource p = JdbcSupport.id(JdbcSupport.params(), "id", id);
        p.addValue("seen", seenAttempt).addValue("hash", requestHash, java.sql.Types.BINARY)
                .addValue("now", now).addValue("lease", newLeaseExpiresAt);
        return jdbc.update("""
                UPDATE IDEMPOTENCY_RECORDS
                   SET STATUS = 'IN_PROGRESS', ATTEMPT = ATTEMPT + 1, LEASE_EXPIRES_AT = :lease
                 WHERE ID = :id AND ATTEMPT = :seen AND REQUEST_HASH = :hash
                   AND (STATUS = 'ABORTED' OR (STATUS = 'IN_PROGRESS' AND LEASE_EXPIRES_AT < :now))
                """, p) == 1;
    }

    @Override
    public Optional<IdempotencyRow> lockForAttempt(UUID id, int attempt) {
        MapSqlParameterSource p = JdbcSupport.id(JdbcSupport.params(), "id", id).addValue("attempt", attempt);
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM IDEMPOTENCY_RECORDS WHERE ID = :id AND ATTEMPT = :attempt "
                        + "AND STATUS = 'IN_PROGRESS' FOR UPDATE WAIT " + lockWait, p, ROW);
        return rows.stream().findFirst();
    }

    @Override
    public boolean complete(UUID id, int attempt, UUID transferId, int responseStatus, String responseBody,
                            OffsetDateTime now, OffsetDateTime replayExpiresAt) {
        MapSqlParameterSource p = JdbcSupport.id(JdbcSupport.params(), "id", id);
        JdbcSupport.id(p, "transfer", transferId);
        p.addValue("attempt", attempt).addValue("status", responseStatus).addValue("body", responseBody)
                .addValue("now", now).addValue("replay", replayExpiresAt);
        return jdbc.update("""
                UPDATE IDEMPOTENCY_RECORDS
                   SET STATUS = 'COMPLETED', LEASE_EXPIRES_AT = NULL, TRANSFER_ID = :transfer,
                       RESPONSE_STATUS = :status, RESPONSE_BODY = :body, COMPLETED_AT = :now,
                       REPLAY_EXPIRES_AT = :replay
                 WHERE ID = :id AND ATTEMPT = :attempt AND STATUS = 'IN_PROGRESS'
                """, p) == 1;
    }

    @Override
    public boolean abort(UUID id, int attempt) {
        MapSqlParameterSource p = JdbcSupport.id(JdbcSupport.params(), "id", id).addValue("attempt", attempt);
        return jdbc.update("""
                UPDATE IDEMPOTENCY_RECORDS SET STATUS = 'ABORTED', LEASE_EXPIRES_AT = NULL
                 WHERE ID = :id AND ATTEMPT = :attempt AND STATUS = 'IN_PROGRESS'
                """, p) == 1;
    }

    @Override
    public int abortStale(OffsetDateTime cutoff) {
        return jdbc.update("""
                UPDATE IDEMPOTENCY_RECORDS SET STATUS = 'ABORTED', LEASE_EXPIRES_AT = NULL
                 WHERE STATUS = 'IN_PROGRESS' AND LEASE_EXPIRES_AT < :cutoff
                """, JdbcSupport.params().addValue("cutoff", cutoff));
    }

    @Override
    public int purgeExpiredBodies(OffsetDateTime now, int batchSize) {
        return jdbc.update("""
                UPDATE IDEMPOTENCY_RECORDS
                   SET RESPONSE_BODY = NULL, RESPONSE_PURGED_AT = :now, REPLAY_EXPIRES_AT = NULL
                 WHERE REPLAY_EXPIRES_AT < :now AND ROWNUM <= :batch
                """, JdbcSupport.params().addValue("now", now).addValue("batch", batchSize));
    }
}
