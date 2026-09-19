package com.enterprise.funds.transfer.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.service.ReviewService;
import com.enterprise.funds.transfer.service.TransferService;
import com.enterprise.funds.transfer.service.TransferService.CreateOutcome;
import com.enterprise.funds.transfer.web.dto.CreateTransferRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Money, idempotency, limits and recovery against a real Oracle. Skipped unless FUNDS_IT_ENABLED=true; see README.
 * NOT RUN YET: written against the design, awaiting a database.
 */
@SpringBootTest(properties = {"funds.jobs.enabled=false", "FUNDS_JWT_ISSUER_URI=https://issuer.invalid/"})
@EnabledIfEnvironmentVariable(named = "FUNDS_IT_ENABLED", matches = "true")
class PostingIT {

    @Autowired TransferService transfers;
    @Autowired ReviewService reviews;
    @MockitoBean JwtDecoder jwtDecoder;

    private OracleFixture db;
    private UUID customerId;
    private UUID destinationCustomerId;
    private Actor alice;
    private OracleFixture.Account source;
    private OracleFixture.Account destination;

    @BeforeEach
    void fixtures() {
        db = new OracleFixture();
        customerId = db.customer();
        alice = db.customerUser(customerId);
        source = db.account(customerId, "1000.00");
        destinationCustomerId = db.customer();
        destination = db.account(destinationCustomerId, "0.00");
    }

    private CreateTransferRequest request(OracleFixture.Account from, OracleFixture.Account to, String amount) {
        return new CreateTransferRequest(from.number(), to.number(), amount, "USD", "it");
    }

    private CreateOutcome create(Actor who, UUID key, CreateTransferRequest req) {
        return transfers.create(who, key.toString(), req, "it-" + key);
    }

    // ------------------------------------------------------------------ accounting

    @Test
    void aPostingMovesMoneyAndWritesTwoBalancedLedgerEntries() {
        CreateOutcome out = create(alice, UUID.randomUUID(), request(source, destination, "250.00"));

        assertThat(out.transfer().status()).isEqualTo("COMPLETED");
        assertThat(db.balance(source)).isEqualByComparingTo("750.00");
        assertThat(db.balance(destination)).isEqualByComparingTo("250.00");
        assertThat(db.availableBalance(source)).isEqualByComparingTo(db.balance(source)); // CK_ACCT_BAL_EQUAL
        assertThat(db.ledgerEntriesForTransfer(out.transferId())).isEqualTo(2);
        BigDecimal debit = db.owner.queryForObject("SELECT SUM(AMOUNT) FROM LEDGER_ENTRIES WHERE TRANSFER_ID = ? AND SIDE = 'DEBIT'",
                BigDecimal.class, Ids.toBytes(out.transferId()));
        BigDecimal credit = db.owner.queryForObject("SELECT SUM(AMOUNT) FROM LEDGER_ENTRIES WHERE TRANSFER_ID = ? AND SIDE = 'CREDIT'",
                BigDecimal.class, Ids.toBytes(out.transferId()));
        assertThat(debit).isEqualByComparingTo(credit);
        assertThat(db.owner.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENTS WHERE TRANSFER_ID = ? AND STATUS = 'PENDING'",
                Integer.class, Ids.toBytes(out.transferId()))).isEqualTo(1);
        assertThat(db.owner.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENTS WHERE ENTITY_ID = ? AND OUTCOME = 'SUCCESS'",
                Integer.class, Ids.toBytes(out.transferId()))).isEqualTo(1);
    }

    @Test
    void insufficientFundsRollsBackEverythingAndIsStoredForReplay() {
        UUID key = UUID.randomUUID();
        var req = request(source, destination, "1000.01");

        assertThatThrownBy(() -> create(alice, key, req)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
        assertThat(db.balance(source)).isEqualByComparingTo("1000.00");
        assertThat(db.balance(destination)).isEqualByComparingTo("0.00");
        assertThat(db.ledgerEntriesFor(source)).isZero();
        assertThat(db.transfersFrom(source)).isZero();

        // a retry replays the stored 422 instead of re-evaluating
        assertThatThrownBy(() -> create(alice, key, req)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
            assertThat(e.headers()).containsEntry("Idempotent-Replayed", "true");
        });
    }

    @Test
    void aTransferFromSomeoneElsesAccountLooksLikeANotFound() {
        Actor mallory = db.customerUser(db.customer());
        assertThatThrownBy(() -> create(mallory, UUID.randomUUID(), request(source, destination, "1.00")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(db.balance(source)).isEqualByComparingTo("1000.00");
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void theSameKeyReplaysAndNeverPostsTwice() {
        UUID key = UUID.randomUUID();
        var req = request(source, destination, "100.00");
        CreateOutcome first = create(alice, key, req);
        CreateOutcome second = create(alice, key, req);

        assertThat(second.replayed()).isTrue();
        assertThat(second.transferId()).isEqualTo(first.transferId());
        assertThat(db.balance(source)).isEqualByComparingTo("900.00");
        assertThat(db.ledgerEntriesFor(source)).isEqualTo(1);
    }

    @Test
    void aDifferentPayloadUnderTheSameKeyIsAConflict() {
        UUID key = UUID.randomUUID();
        create(alice, key, request(source, destination, "100.00"));
        assertThatThrownBy(() -> create(alice, key, request(source, destination, "200.00")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(db.balance(source)).isEqualByComparingTo("900.00");
    }

    @Test
    void anExpiredKeyNeverPostsAgain() {
        UUID key = UUID.randomUUID();
        var req = request(source, destination, "100.00");
        CreateOutcome first = create(alice, key, req);

        // age the replay window past its end, exactly as time would
        db.owner.update("UPDATE IDEMPOTENCY_RECORDS SET REPLAY_EXPIRES_AT = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE TRANSFER_ID = ?",
                Ids.toBytes(first.transferId()));

        assertThatThrownBy(() -> create(alice, key, req)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
            assertThat(e.headers()).containsEntry("Location", "/api/v1/transfers/" + first.transferId());
        });
        assertThat(db.balance(source)).isEqualByComparingTo("900.00"); // unchanged: no second posting
        assertThat(db.ledgerEntriesFor(source)).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalRequestsPostExactlyOnce() throws Exception {
        UUID key = UUID.randomUUID();
        var req = request(source, destination, "100.00");
        List<Object> results = race(8, i -> () -> create(alice, key, req));

        long created = results.stream().filter(r -> r instanceof CreateOutcome o && !o.replayed()).count();
        assertThat(created).isEqualTo(1);
        assertThat(results).allSatisfy(r -> {
            if (r instanceof ApiException e) {
                assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
            }
        });
        assertThat(db.balance(source)).isEqualByComparingTo("900.00");
        assertThat(db.ledgerEntriesFor(source)).isEqualTo(1);
    }

    @Test
    void anAbandonedAttemptIsRecoveredAndPostsOnce() {
        UUID key = UUID.randomUUID();
        var req = request(source, destination, "100.00");
        // simulate a crash after the key was claimed: an IN_PROGRESS record whose lease already expired
        byte[] hash = new com.enterprise.funds.transfer.service.RequestHasher()
                .hash(source.number(), destination.number(), new BigDecimal("100.00"), "USD", "it");
        db.owner.update("INSERT INTO IDEMPOTENCY_RECORDS (ID, USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, STATUS, ATTEMPT, LEASE_EXPIRES_AT) "
                + "VALUES (?, ?, ?, ?, 'IN_PROGRESS', 1, SYSTIMESTAMP - INTERVAL '5' MINUTE)",
                Ids.toBytes(Ids.newId()), Ids.toBytes(alice.userId()), Ids.toBytes(key), hash);

        CreateOutcome out = create(alice, key, req);

        assertThat(out.replayed()).isFalse();
        assertThat(db.balance(source)).isEqualByComparingTo("900.00");
        assertThat(db.owner.queryForObject("SELECT ATTEMPT FROM IDEMPOTENCY_RECORDS WHERE TRANSFER_ID = ?", Integer.class,
                Ids.toBytes(out.transferId()))).isEqualTo(2); // taken over, fencing token bumped
    }

    // ------------------------------------------------------------------ limits under concurrency

    @Test
    void twoConcurrentTransfersCannotJointlyExceedAnAccountDailyLimit() throws Exception {
        db.accountLimit(source, "DAILY", "1000.00");
        List<Object> results = race(2, i -> () -> create(alice, UUID.randomUUID(), request(source, destination, "600.00")));

        assertThat(results.stream().filter(r -> r instanceof CreateOutcome).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r instanceof ApiException e && e.code() == ErrorCode.LIMIT_EXCEEDED).count()).isEqualTo(1);
        assertThat(db.totalDebits(source)).isEqualByComparingTo("600.00");
    }

    @Test
    void twoConcurrentTransfersFromDifferentAccountsCannotExceedACustomerLimit() throws Exception {
        var second = db.account(customerId, "1000.00");
        db.customerLimit(customerId, "DAILY", "1000.00");
        List<Object> results = race(2, i -> () ->
                create(alice, UUID.randomUUID(), request(i == 0 ? source : second, destination, "600.00")));

        assertThat(results.stream().filter(r -> r instanceof CreateOutcome).count()).isEqualTo(1);
        assertThat(db.totalDebits(source, second)).isEqualByComparingTo("600.00");
    }

    @Test
    void transfersInOppositeDirectionsDoNotDeadlock() throws Exception {
        var a = db.account(customerId, "1000.00");
        var b = db.account(customerId, "1000.00");
        List<Object> results = race(20, i -> () -> create(alice, UUID.randomUUID(),
                i % 2 == 0 ? request(a, b, "1.00") : request(b, a, "1.00")));

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(CreateOutcome.class));
        assertThat(db.balance(a).add(db.balance(b))).isEqualByComparingTo("2000.00"); // money is conserved
    }

    // ------------------------------------------------------------------ review and cancel

    @Test
    void aTransferOverTheReviewThresholdWaitsAndMovesNoMoney() {
        var rich = db.account(customerId, "50000.00");
        CreateOutcome out = create(alice, UUID.randomUUID(), request(rich, destination, "20000.00"));

        assertThat(out.transfer().status()).isEqualTo("PENDING_REVIEW");
        assertThat(db.balance(rich)).isEqualByComparingTo("50000.00");
        assertThat(db.ledgerEntriesFor(rich)).isZero();
    }

    @Test
    void reviewApprovalPostsAndRechecksFundsUnderLock() {
        var rich = db.account(customerId, "50000.00");
        CreateOutcome out = create(alice, UUID.randomUUID(), request(rich, destination, "20000.00"));
        Actor operator = db.operator();

        // funds disappear while the transfer waits: nothing was reserved (V1 has no holds)
        db.owner.update("UPDATE ACCOUNT_BALANCES SET AVAILABLE_BALANCE = 100, LEDGER_BALANCE = 100 WHERE ACCOUNT_ID = ?", Ids.toBytes(rich.id()));
        var after = reviews.approve(operator, out.transferId(), "it");

        assertThat(after.status().name()).isEqualTo("REJECTED");
        assertThat(db.balance(rich)).isEqualByComparingTo("100");
        assertThat(db.ledgerEntriesFor(rich)).isZero();
    }

    @Test
    void cancelRulesFollowTheContract() {
        var rich = db.account(customerId, "50000.00");
        CreateOutcome held = create(alice, UUID.randomUUID(), request(rich, destination, "20000.00"));
        assertThat(transfers.cancel(alice, held.transferId(), "it").status().name()).isEqualTo("CANCELLED");
        assertThat(transfers.cancel(alice, held.transferId(), "it").status().name()).isEqualTo("CANCELLED"); // idempotent

        CreateOutcome posted = create(alice, UUID.randomUUID(), request(source, destination, "10.00"));
        assertThatThrownBy(() -> transfers.cancel(alice, posted.transferId(), "it")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.TRANSFER_NOT_CANCELLABLE));

        // a destination-only owner can see the transfer but not cancel it: 403, not 404
        Actor bob = db.customerUser(destinationCustomerId);
        assertThatThrownBy(() -> transfers.cancel(bob, posted.transferId(), "it")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        // an unrelated customer cannot even tell it exists
        Actor eve = db.customerUser(db.customer());
        assertThatThrownBy(() -> transfers.cancel(eve, posted.transferId(), "it")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ------------------------------------------------------------------ helpers

    /** Starts n tasks together and returns each one's result or thrown ApiException. */
    private List<Object> race(int n, java.util.function.IntFunction<Callable<CreateOutcome>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<CreateOutcome> body = task.apply(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return body.call();
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }
}
