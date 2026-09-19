package com.enterprise.funds.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.funds.transfer.TestProps;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.service.IdempotencyService.Begin;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One test per row of the policy table in docs/milestone-1-contract.md section 4. */
class IdempotencyServiceTest {

    private static final Duration REPLAY = Duration.ofDays(7);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private InMemoryIdempotencyRepository repo;
    private MutableClock clock;
    private IdempotencyService service;
    private final UUID user = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final byte[] hash = new RequestHasher().hash("A", "B", BigDecimal.TEN, "USD", null);
    private final byte[] otherHash = new RequestHasher().hash("A", "B", BigDecimal.ONE, "USD", null);

    @BeforeEach
    void setUp() {
        repo = new InMemoryIdempotencyRepository();
        clock = new MutableClock(Instant.parse("2026-09-18T10:00:00Z"));
        service = new IdempotencyService(repo,
                TestProps.props(REPLAY, LEASE, Duration.ofDays(90), BigDecimal.TEN, null), clock);
    }

    private Begin.Proceed proceed(Begin b) {
        assertThat(b).isInstanceOf(Begin.Proceed.class);
        return (Begin.Proceed) b;
    }

    private ApiException failure(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected an ApiException");
    }

    // ---- first request

    @Test
    void firstRequestOwnsTheKeyAsAttemptOne() {
        var attempt = proceed(service.begin(user, key, hash));
        assertThat(attempt.attempt()).isEqualTo(1);
        assertThat(repo.byId(attempt.recordId()).status()).isEqualTo("IN_PROGRESS");
    }

    // ---- replay and concurrency

    @Test
    void sameKeyAndPayloadAfterCompletionReplaysTheOriginal() {
        var attempt = proceed(service.begin(user, key, hash));
        UUID transfer = UUID.randomUUID();
        assertThat(service.complete(attempt, transfer, 201, "{\"status\":\"COMPLETED\"}")).isTrue();

        clock.advance(Duration.ofHours(1));
        var replay = (Begin.Replay) service.begin(user, key, hash);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo("{\"status\":\"COMPLETED\"}");
        assertThat(replay.transferId()).isEqualTo(transfer);
        assertThat(repo.size()).isEqualTo(1); // nothing new was created
    }

    @Test
    void aStored422IsReplayedToo() {
        var attempt = proceed(service.begin(user, key, hash));
        service.complete(attempt, null, 422, "{\"code\":\"INSUFFICIENT_FUNDS\"}");
        var replay = (Begin.Replay) service.begin(user, key, hash);
        assertThat(replay.status()).isEqualTo(422);
    }

    @Test
    void differentPayloadIsAConflict() {
        service.begin(user, key, hash);
        assertThat(failure(() -> service.begin(user, key, otherHash)).code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void concurrentRequestWhileFirstIsRunningIsAConflictWithRetryAfter() {
        service.begin(user, key, hash);
        ApiException e = failure(() -> service.begin(user, key, hash));
        assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        assertThat(e.status()).isEqualTo(409);
        assertThat(e.headers()).containsEntry("Retry-After", "1");
    }

    @Test
    void theKeyIsScopedToTheActor() {
        service.begin(user, key, hash);
        UUID someoneElse = UUID.randomUUID();
        assertThat(service.begin(someoneElse, key, hash)).isInstanceOf(Begin.Proceed.class); // independent
        assertThat(repo.size()).isEqualTo(2);
    }

    // ---- expiry: the guarantee that an expired key can never double-post

    @Test
    void afterTheReplayWindowTheKeyIsExpiredNotProcessedAgain() {
        var attempt = proceed(service.begin(user, key, hash));
        UUID transfer = UUID.randomUUID();
        service.complete(attempt, transfer, 201, "{}");

        clock.advance(REPLAY.plusSeconds(1));
        ApiException e = failure(() -> service.begin(user, key, hash));
        assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
        assertThat(e.status()).isEqualTo(409);
        assertThat(e.headers()).containsEntry("Location", "/api/v1/transfers/" + transfer);
        assertThat(repo.size()).isEqualTo(1);
        assertThat(repo.byId(attempt.recordId()).status()).isEqualTo("COMPLETED"); // still recorded, never reset
    }

    @Test
    void expiredKeyWithoutATransferHasNoLocation() {
        var attempt = proceed(service.begin(user, key, hash));
        service.complete(attempt, null, 422, "{}");
        clock.advance(REPLAY.plusSeconds(1));
        assertThat(failure(() -> service.begin(user, key, hash)).headers()).doesNotContainKey("Location");
    }

    @Test
    void purgingTheBodyDoesNotForgetTheKey() {
        var attempt = proceed(service.begin(user, key, hash));
        service.complete(attempt, UUID.randomUUID(), 201, "{}");
        clock.advance(REPLAY.plusSeconds(1));
        assertThat(repo.purgeExpiredBodies(java.time.OffsetDateTime.now(clock), 10)).isEqualTo(1);
        assertThat(repo.byId(attempt.recordId()).responseBody()).isNull();

        assertThat(failure(() -> service.begin(user, key, hash)).code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
        assertThat(repo.size()).isEqualTo(1);
    }

    @Test
    void afterExpiryADifferentPayloadIsStillAConflictNotANewRequest() {
        var attempt = proceed(service.begin(user, key, hash));
        service.complete(attempt, UUID.randomUUID(), 201, "{}");
        clock.advance(REPLAY.multipliedBy(10));
        assertThat(failure(() -> service.begin(user, key, otherHash)).code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void expiryHonoursTheWindowEvenIfThePurgeJobHasNotRun() {
        var attempt = proceed(service.begin(user, key, hash));
        service.complete(attempt, UUID.randomUUID(), 201, "{}");
        clock.advance(REPLAY.plusSeconds(1));
        // no purge: the body is still stored, but the window is over
        assertThat(repo.byId(attempt.recordId()).responseBody()).isNotNull();
        assertThat(failure(() -> service.begin(user, key, hash)).code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
    }

    // ---- recovery

    @Test
    void anAttemptThatDiedIsTakenOverOnceItsLeaseExpires() {
        var first = proceed(service.begin(user, key, hash));
        clock.advance(LEASE.plusSeconds(1));

        var second = proceed(service.begin(user, key, hash));
        assertThat(second.recordId()).isEqualTo(first.recordId());
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(repo.size()).isEqualTo(1);
    }

    @Test
    void anAttemptIsNotTakenOverWhileItsLeaseIsLive() {
        service.begin(user, key, hash);
        clock.advance(LEASE.minusSeconds(1));
        assertThat(failure(() -> service.begin(user, key, hash)).code()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }

    @Test
    void anAbortedAttemptCanBeRetriedImmediately() {
        var first = proceed(service.begin(user, key, hash));
        service.abort(first);
        var second = proceed(service.begin(user, key, hash));
        assertThat(second.attempt()).isEqualTo(2);
    }

    @Test
    void abortedRecordStillRejectsADifferentPayload() {
        var first = proceed(service.begin(user, key, hash));
        service.abort(first);
        assertThat(failure(() -> service.begin(user, key, otherHash)).code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void takeoverIsCompareAndSetSoOnlyOneRetryWins() {
        var first = proceed(service.begin(user, key, hash));
        clock.advance(LEASE.plusSeconds(1));
        var now = java.time.OffsetDateTime.now(clock);

        boolean winner = repo.takeover(first.recordId(), 1, hash, now, now.plus(LEASE));
        boolean loser = repo.takeover(first.recordId(), 1, hash, now, now.plus(LEASE)); // same stale view
        assertThat(winner).isTrue();
        assertThat(loser).isFalse();
        assertThat(repo.byId(first.recordId()).attempt()).isEqualTo(2);
    }

    @Test
    void aSupersededAttemptIsFencedOutAndCannotComplete() {
        var zombie = proceed(service.begin(user, key, hash));
        clock.advance(LEASE.plusSeconds(1));
        var replacement = proceed(service.begin(user, key, hash)); // takes over: attempt 2

        assertThatThrownBy(() -> service.requireOwnership(zombie)).isInstanceOf(FencedOutException.class);
        assertThat(service.complete(zombie, UUID.randomUUID(), 201, "{}")).isFalse(); // cannot report success
        assertThat(service.complete(replacement, UUID.randomUUID(), 201, "{}")).isTrue();
    }

    @Test
    void aSupersededAttemptCannotAbortTheReplacement() {
        var zombie = proceed(service.begin(user, key, hash));
        clock.advance(LEASE.plusSeconds(1));
        var replacement = proceed(service.begin(user, key, hash));
        service.abort(zombie); // stale token: must be a no-op
        assertThat(repo.byId(replacement.recordId()).status()).isEqualTo("IN_PROGRESS");
        assertThat(repo.byId(replacement.recordId()).attempt()).isEqualTo(2);
    }

    @Test
    void theOwnerPassesTheOwnershipCheck() {
        service.requireOwnership(proceed(service.begin(user, key, hash)));
    }

    @Test
    void reconcilerAbortsStaleAttemptsButNotLiveOnes() {
        service.begin(user, key, hash);
        service.begin(user, UUID.randomUUID(), hash);
        clock.advance(LEASE.plusSeconds(1));
        service.begin(user, UUID.randomUUID(), hash); // a fresh, live attempt

        int closed = repo.abortStale(java.time.OffsetDateTime.now(clock));
        assertThat(closed).isEqualTo(2);
    }

    @Test
    void completedRecordsAreNeverTouchedByTheReconciler() {
        var attempt = proceed(service.begin(user, key, hash));
        service.complete(attempt, UUID.randomUUID(), 201, "{}");
        clock.advance(Duration.ofDays(365));
        assertThat(repo.abortStale(java.time.OffsetDateTime.now(clock))).isZero();
        assertThat(repo.byId(attempt.recordId()).status()).isEqualTo("COMPLETED");
    }
}
