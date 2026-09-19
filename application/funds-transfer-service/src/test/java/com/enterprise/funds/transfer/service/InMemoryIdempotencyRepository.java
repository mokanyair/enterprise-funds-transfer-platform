package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.persistence.IdempotencyRepository;
import com.enterprise.funds.transfer.persistence.Rows.IdempotencyRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Behaves like the SQL in JdbcIdempotencyRepository (same compare-and-set conditions), single-threaded, so the
 * decision logic can be tested without Oracle. The Oracle-backed behaviour is covered by the *IT classes.
 */
class InMemoryIdempotencyRepository implements IdempotencyRepository {

    private final Map<UUID, IdempotencyRow> rows = new LinkedHashMap<>();

    IdempotencyRow byId(UUID id) { return rows.get(id); }

    void put(IdempotencyRow row) { rows.put(row.id(), row); }

    int size() { return rows.size(); }

    @Override
    public boolean insertInProgress(UUID id, UUID userId, UUID key, byte[] hash, OffsetDateTime lease, OffsetDateTime now) {
        if (find(userId, key).isPresent()) {
            return false; // UNIQUE (USER_ID, IDEMPOTENCY_KEY)
        }
        rows.put(id, new IdempotencyRow(id, userId, key, hash, "IN_PROGRESS", 1, lease, null, null, null, null, null));
        return true;
    }

    @Override
    public Optional<IdempotencyRow> find(UUID userId, UUID key) {
        return rows.values().stream().filter(r -> r.userId().equals(userId) && r.key().equals(key)).findFirst();
    }

    @Override
    public boolean takeover(UUID id, int seen, byte[] hash, OffsetDateTime now, OffsetDateTime newLease) {
        IdempotencyRow r = rows.get(id);
        boolean eligible = r != null && r.attempt() == seen && Arrays.equals(r.requestHash(), hash)
                && ("ABORTED".equals(r.status())
                || ("IN_PROGRESS".equals(r.status()) && r.leaseExpiresAt().isBefore(now)));
        if (!eligible) {
            return false;
        }
        rows.put(id, new IdempotencyRow(id, r.userId(), r.key(), r.requestHash(), "IN_PROGRESS", seen + 1, newLease,
                null, null, null, null, null));
        return true;
    }

    @Override
    public Optional<IdempotencyRow> lockForAttempt(UUID id, int attempt) {
        IdempotencyRow r = rows.get(id);
        return r != null && r.attempt() == attempt && "IN_PROGRESS".equals(r.status()) ? Optional.of(r) : Optional.empty();
    }

    @Override
    public boolean complete(UUID id, int attempt, UUID transferId, int status, String body, OffsetDateTime now,
                            OffsetDateTime replayExpiresAt) {
        if (lockForAttempt(id, attempt).isEmpty()) {
            return false;
        }
        IdempotencyRow r = rows.get(id);
        rows.put(id, new IdempotencyRow(id, r.userId(), r.key(), r.requestHash(), "COMPLETED", attempt, null, transferId,
                status, body, replayExpiresAt, null));
        return true;
    }

    @Override
    public boolean abort(UUID id, int attempt) {
        if (lockForAttempt(id, attempt).isEmpty()) {
            return false;
        }
        IdempotencyRow r = rows.get(id);
        rows.put(id, new IdempotencyRow(id, r.userId(), r.key(), r.requestHash(), "ABORTED", attempt, null, null, null,
                null, null, null));
        return true;
    }

    @Override
    public int abortStale(OffsetDateTime cutoff) {
        int n = 0;
        for (IdempotencyRow r : new ArrayList<>(rows.values())) {
            if ("IN_PROGRESS".equals(r.status()) && r.leaseExpiresAt().isBefore(cutoff)) {
                abort(r.id(), r.attempt());
                n++;
            }
        }
        return n;
    }

    @Override
    public int purgeExpiredBodies(OffsetDateTime now, int batch) {
        int n = 0;
        for (IdempotencyRow r : new ArrayList<>(rows.values())) {
            if (n < batch && r.replayExpiresAt() != null && r.replayExpiresAt().isBefore(now)) {
                rows.put(r.id(), new IdempotencyRow(r.id(), r.userId(), r.key(), r.requestHash(), r.status(), r.attempt(), null,
                        r.transferId(), r.responseStatus(), null, null, now));
                n++;
            }
        }
        return n;
    }
}
