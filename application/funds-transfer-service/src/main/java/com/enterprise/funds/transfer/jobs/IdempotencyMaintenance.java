package com.enterprise.funds.transfer.jobs;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.persistence.IdempotencyRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovery and retention for idempotency records (design: docs/milestone-2-erd.md section 11).
 * Correctness never depends on these running on time: takeover checks the lease itself and replay checks
 * REPLAY_EXPIRES_AT itself. They keep the table tidy and the indexes small.
 *
 * The tombstone purge (DELETE of old records with no transfer) is deliberately NOT here: the runtime identity has
 * no DELETE privilege (design P8). It belongs to a separately privileged job.
 */
@Component
@ConditionalOnProperty(prefix = "funds.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyMaintenance.class);

    private final IdempotencyRepository repo;
    private final FundsProperties props;
    private final Clock clock;

    public IdempotencyMaintenance(IdempotencyRepository repo, FundsProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    /** Closes attempts that died mid-flight. Nothing they did was committed, so ABORTED is always correct. */
    @Scheduled(fixedDelayString = "${funds.jobs.reconcile-interval:30s}")
    public void abortStaleAttempts() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(props.idempotency().abortGrace());
        int closed = repo.abortStale(cutoff);
        if (closed > 0) {
            LOG.warn("Reconciler aborted {} stale IN_PROGRESS idempotency record(s)", closed);
        }
    }

    /** Drops stored response bodies past the replay window. The key record stays, so the key is never reusable. */
    @Scheduled(fixedDelayString = "${funds.jobs.purge-interval:10m}")
    public void purgeExpiredBodies() {
        int purged = repo.purgeExpiredBodies(OffsetDateTime.now(clock), props.jobs().purgeBatch());
        if (purged > 0) {
            LOG.info("Purged {} expired idempotent response bodies", purged);
        }
    }
}
