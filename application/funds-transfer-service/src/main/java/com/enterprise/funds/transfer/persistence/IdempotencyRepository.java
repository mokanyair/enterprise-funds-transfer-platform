package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.persistence.Rows.IdempotencyRow;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage port for idempotency records (design: docs/milestone-2-erd.md section 11). Kept as an interface so the
 * decision logic in IdempotencyService can be unit-tested without a database.
 */
public interface IdempotencyRepository {

    /** Inserts an IN_PROGRESS record (attempt 1). Returns false if (user, key) already exists. */
    boolean insertInProgress(UUID id, UUID userId, UUID key, byte[] requestHash,
                             OffsetDateTime leaseExpiresAt, OffsetDateTime now);

    Optional<IdempotencyRow> find(UUID userId, UUID key);

    /**
     * Compare-and-set takeover of an abandoned attempt (ABORTED, or IN_PROGRESS with an expired lease).
     * Exactly one caller sees true; that caller now owns attempt seenAttempt + 1.
     */
    boolean takeover(UUID id, int seenAttempt, byte[] requestHash, OffsetDateTime now, OffsetDateTime newLeaseExpiresAt);

    /**
     * Locks the record and returns it only if it is still IN_PROGRESS for this attempt. Empty means this attempt
     * was superseded (fenced out) and must not post. Must be the first lock taken in the posting transaction.
     */
    Optional<IdempotencyRow> lockForAttempt(UUID id, int attempt);

    /** Completes the record for this attempt with the stored outcome. False if the attempt no longer owns it. */
    boolean complete(UUID id, int attempt, UUID transferId, int responseStatus, String responseBody,
                     OffsetDateTime now, OffsetDateTime replayExpiresAt);

    /** Marks this attempt ABORTED (nothing was posted). False if the attempt no longer owns the record. */
    boolean abort(UUID id, int attempt);

    /** Reconciler: abort IN_PROGRESS records whose lease expired before the cutoff. Returns rows changed. */
    int abortStale(OffsetDateTime cutoff);

    /** Purges stored bodies past their replay window. The record itself stays. Returns rows changed. */
    int purgeExpiredBodies(OffsetDateTime now, int batchSize);
}
