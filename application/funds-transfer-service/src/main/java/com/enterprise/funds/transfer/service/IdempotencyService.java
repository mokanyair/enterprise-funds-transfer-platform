package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.persistence.IdempotencyRepository;
import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.persistence.Rows.IdempotencyRow;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Decides what to do with a request's Idempotency-Key. Implements the policy in
 * docs/milestone-1-contract.md section 4 and the lifecycle in docs/milestone-2-erd.md section 11.
 */
@Service
public class IdempotencyService {

    /** Result of {@link #begin}. Everything else is signalled with an ApiException (409). */
    public sealed interface Begin permits Begin.Proceed, Begin.Replay {
        /** This request owns the key and must now do the work. attempt is the fencing token. */
        record Proceed(UUID recordId, int attempt) implements Begin {}

        /** The original outcome, still inside its replay window. */
        record Replay(int status, String body, UUID transferId) implements Begin {}
    }

    private static final int MAX_TAKEOVER_ROUNDS = 3;
    static final String TRANSFER_PATH = "/api/v1/transfers/";

    private final IdempotencyRepository repo;
    private final FundsProperties.Idempotency config;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repo, FundsProperties props, Clock clock) {
        this.repo = repo;
        this.config = props.idempotency();
        this.clock = clock;
    }

    public Begin begin(UUID userId, UUID key, byte[] requestHash) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID id = Ids.newId();
        if (repo.insertInProgress(id, userId, key, requestHash, now.plus(config.lease()), now)) {
            return new Begin.Proceed(id, 1);
        }
        for (int round = 0; round < MAX_TAKEOVER_ROUNDS; round++) {
            IdempotencyRow row = repo.find(userId, key).orElse(null);
            now = OffsetDateTime.now(clock);
            if (row == null) { // purged between our failed insert and this read: try to claim it again
                if (repo.insertInProgress(id, userId, key, requestHash, now.plus(config.lease()), now)) {
                    return new Begin.Proceed(id, 1);
                }
                continue;
            }
            if (!MessageDigest.isEqual(row.requestHash(), requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            switch (row.status()) {
                case "COMPLETED" -> {
                    return completed(row, now);
                }
                case "IN_PROGRESS" -> {
                    if (row.leaseExpiresAt() != null && row.leaseExpiresAt().isAfter(now)) {
                        throw inProgress();
                    }
                    // lease expired: the attempt died or is very slow; fall through to takeover
                }
                case "ABORTED" -> { /* nothing was posted; a retry may take over */ }
                default -> throw new IllegalStateException("Unknown idempotency status " + row.status());
            }
            if (repo.takeover(row.id(), row.attempt(), requestHash, now, now.plus(config.lease()))) {
                return new Begin.Proceed(row.id(), row.attempt() + 1);
            }
            // lost the race to another retry: read again and answer from the winner's state
        }
        throw inProgress();
    }

    /** Stores the outcome for this attempt. False means the attempt was superseded and must not report success. */
    public boolean complete(Begin.Proceed attempt, UUID transferId, int status, String body) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return repo.complete(attempt.recordId(), attempt.attempt(), transferId, status, body, now,
                now.plus(config.replayRetention()));
    }

    /** Releases this attempt after a failure that posted nothing, so a retry can proceed immediately. */
    public void abort(Begin.Proceed attempt) {
        repo.abort(attempt.recordId(), attempt.attempt());
    }

    /** First statement of the posting transaction: lock the record and confirm this attempt still owns it. */
    public void requireOwnership(Begin.Proceed attempt) {
        if (repo.lockForAttempt(attempt.recordId(), attempt.attempt()).isEmpty()) {
            throw new FencedOutException();
        }
    }

    public ApiException inProgress() {
        return new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS)
                .header("Retry-After", Long.toString(Math.max(1, config.inProgressRetryAfter().toSeconds())));
    }

    private Begin completed(IdempotencyRow row, OffsetDateTime now) {
        boolean replayable = row.responsePurgedAt() == null && row.replayExpiresAt() != null
                && row.replayExpiresAt().isAfter(now) && row.responseBody() != null && row.responseStatus() != null;
        if (replayable) {
            return new Begin.Replay(row.responseStatus(), row.responseBody(), row.transferId());
        }
        // Window elapsed: the key is still remembered, so the request is NOT processed again.
        ApiException expired = new ApiException(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
        if (row.transferId() != null) {
            expired.header("Location", TRANSFER_PATH + row.transferId());
        }
        throw expired;
    }
}
