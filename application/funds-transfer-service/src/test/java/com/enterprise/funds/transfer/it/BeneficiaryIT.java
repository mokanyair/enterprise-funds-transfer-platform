package com.enterprise.funds.transfer.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.service.BeneficiaryService;
import com.enterprise.funds.transfer.web.dto.BeneficiaryDto;
import com.enterprise.funds.transfer.web.dto.CreateBeneficiaryRequest;
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
 * Beneficiaries against a real Oracle (V7): the customer-row lock under concurrency, UX_BENEF_ACTIVE, soft delete and
 * TRG_BENEF_IMMUTABLE, all through the runtime identity. Skipped unless FUNDS_IT_ENABLED=true; see README.
 * Caps are lowered for the concurrency tests so they stay fast.
 */
@SpringBootTest(properties = {"funds.jobs.enabled=false", "FUNDS_JWT_ISSUER_URI=https://issuer.invalid/",
        "funds.beneficiaries.max-active=5", "funds.beneficiaries.max-adds-per-day=8"})
@EnabledIfEnvironmentVariable(named = "FUNDS_IT_ENABLED", matches = "true")
class BeneficiaryIT {

    private static final int MAX_ACTIVE = 5;
    private static final int MAX_ADDS_PER_DAY = 8;

    @Autowired BeneficiaryService beneficiaries;
    @MockitoBean JwtDecoder jwtDecoder;

    private OracleFixture db;
    private UUID customerId;
    private Actor alice;
    private Actor bob; // same customer as alice (B2)

    @BeforeEach
    void fixtures() {
        db = new OracleFixture();
        customerId = db.customer();
        alice = db.customerUser(customerId);
        bob = db.customerUser(customerId);
    }

    private OracleFixture.Account otherCustomersAccount() {
        return db.account(db.customer(), "0.00");
    }

    private BeneficiaryDto add(Actor who, OracleFixture.Account account, String nickname) {
        return beneficiaries.create(who, new CreateBeneficiaryRequest(account.number(), nickname), "it-" + UUID.randomUUID());
    }

    private static ErrorCode codeOf(Callable<?> call) {
        try {
            call.call();
        } catch (ApiException e) {
            return e.code();
        } catch (Exception e) {
            throw new AssertionError("unexpected " + e, e);
        }
        throw new AssertionError("expected an ApiException");
    }

    private int rows(String where, Object... args) {
        return db.owner.queryForObject("SELECT COUNT(*) FROM BENEFICIARIES WHERE CUSTOMER_ID = ? AND " + where,
                Integer.class, prepend(Ids.toBytes(customerId), args));
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] all = new Object[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    /** Runs the same call from N threads released together; returns each outcome (a result or an ApiException). */
    private static <T> List<Object> race(int threads, Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (ApiException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                outcomes.add(f.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void addListGetDeleteAndReAdd() {
        var target = otherCustomersAccount();
        BeneficiaryDto added = add(alice, target, "  Rent - landlord ");
        assertThat(added.nickname()).isEqualTo("Rent - landlord");
        assertThat(added.accountId()).isEqualTo(target.number());

        // Shared by every user of the customer (B2).
        assertThat(beneficiaries.list(bob).beneficiaries()).extracting(BeneficiaryDto::beneficiaryId)
                .containsExactly(added.beneficiaryId());
        UUID id = UUID.fromString(added.beneficiaryId());
        assertThat(beneficiaries.get(bob, id).accountId()).isEqualTo(target.number());

        beneficiaries.delete(bob, id, "it-del");
        beneficiaries.delete(alice, id, "it-del-again"); // idempotent (B7)
        assertThat(beneficiaries.list(alice).beneficiaries()).isEmpty();
        assertThat(codeOf(() -> beneficiaries.get(alice, id))).isEqualTo(ErrorCode.NOT_FOUND);

        BeneficiaryDto again = add(alice, target, "Rent - landlord");
        assertThat(again.beneficiaryId()).isNotEqualTo(added.beneficiaryId());
        assertThat(rows("ACCOUNT_ID = ?", Ids.toBytes(target.id()))).isEqualTo(2);
        assertThat(rows("STATUS = 'DELETED' AND DELETED_BY_USER_ID = ?", Ids.toBytes(bob.userId()))).isEqualTo(1);

        int audits = db.owner.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_EVENTS WHERE ENTITY_ID = ? AND ACTION = 'BENEFICIARY_DELETE'",
                Integer.class, Ids.toBytes(id));
        assertThat(audits).as("a repeated delete writes no second audit row").isEqualTo(1);
    }

    @Test
    void listIsOrderedByNicknameIgnoringCase() {
        add(alice, otherCustomersAccount(), "bravo");
        add(alice, otherCustomersAccount(), "Alpha");
        add(alice, otherCustomersAccount(), "charlie");
        assertThat(beneficiaries.list(alice).beneficiaries()).extracting(BeneficiaryDto::nickname)
                .containsExactly("Alpha", "bravo", "charlie");
    }

    @Test
    void anotherCustomersBeneficiaryIsInvisible() {
        BeneficiaryDto added = add(alice, otherCustomersAccount(), "mine");
        UUID id = UUID.fromString(added.beneficiaryId());
        Actor stranger = db.customerUser(db.customer());

        assertThat(codeOf(() -> beneficiaries.get(stranger, id))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> { beneficiaries.delete(stranger, id, "it"); return null; })).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(beneficiaries.list(stranger).beneficiaries()).isEmpty();
        assertThat(rows("STATUS = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void rejectionsFollowTheContract() {
        var mine = db.account(customerId, "0.00");
        var frozen = otherCustomersAccount();
        db.owner.update("UPDATE ACCOUNTS SET STATUS = 'FROZEN' WHERE ACCOUNT_ID = ?", Ids.toBytes(frozen.id()));

        assertThat(codeOf(() -> add(alice, mine, "x"))).isEqualTo(ErrorCode.BENEFICIARY_OWN_ACCOUNT);
        assertThat(codeOf(() -> add(alice, frozen, "x"))).isEqualTo(ErrorCode.ACCOUNT_INACTIVE);
        assertThat(codeOf(() -> beneficiaries.create(alice, new CreateBeneficiaryRequest("NOSUCH" + System.nanoTime(), "x"), "it")))
                .isEqualTo(ErrorCode.ACCOUNT_INACTIVE);
        assertThat(rows("1 = 1")).isZero();

        int failures = db.owner.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENTS WHERE ENTITY_ID = ? "
                + "AND ACTION = 'BENEFICIARY_ADD' AND OUTCOME = 'FAILURE'", Integer.class, Ids.toBytes(customerId));
        assertThat(failures).isEqualTo(3);
    }

    // ------------------------------------------------------------------ concurrency under the customer lock

    @Test
    void parallelAddsOfTheSameAccountCreateExactlyOne() throws Exception {
        var target = otherCustomersAccount();
        List<Object> outcomes = race(6, () -> add(alice, target, "same"));

        long created = outcomes.stream().filter(BeneficiaryDto.class::isInstance).count();
        assertThat(created).isEqualTo(1);
        BeneficiaryDto winner = (BeneficiaryDto) outcomes.stream().filter(BeneficiaryDto.class::isInstance).findFirst().orElseThrow();
        assertThat(outcomes).filteredOn(ApiException.class::isInstance).allSatisfy(o -> {
            ApiException e = (ApiException) o;
            assertThat(e.code()).isEqualTo(ErrorCode.BENEFICIARY_ALREADY_EXISTS);
            assertThat(e.headers()).containsEntry("Location", "/api/v1/beneficiaries/" + winner.beneficiaryId());
        });
        assertThat(rows("ACCOUNT_ID = ?", Ids.toBytes(target.id()))).isEqualTo(1);
    }

    @Test
    void parallelAddsCannotExceedTheActiveCap() throws Exception {
        for (int i = 0; i < MAX_ACTIVE - 1; i++) {
            add(alice, otherCustomersAccount(), "pre-" + i);
        }
        List<OracleFixture.Account> targets = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            targets.add(otherCustomersAccount());
        }
        var next = new java.util.concurrent.atomic.AtomicInteger();
        List<Object> outcomes = race(4, () -> add(alice, targets.get(next.getAndIncrement()), "race"));

        assertThat(outcomes).filteredOn(BeneficiaryDto.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ApiException.class::isInstance)
                .allSatisfy(o -> assertThat(((ApiException) o).code()).isEqualTo(ErrorCode.BENEFICIARY_LIMIT_REACHED));
        assertThat(rows("STATUS = 'ACTIVE'")).isEqualTo(MAX_ACTIVE);
    }

    @Test
    void deletingDoesNotRefundTheDailyAddCap() {
        for (int i = 0; i < MAX_ADDS_PER_DAY; i++) {
            BeneficiaryDto b = add(alice, otherCustomersAccount(), "churn-" + i);
            beneficiaries.delete(alice, UUID.fromString(b.beneficiaryId()), "it");
        }
        assertThat(rows("STATUS = 'ACTIVE'")).isZero();
        assertThat(codeOf(() -> add(alice, otherCustomersAccount(), "one more"))).isEqualTo(ErrorCode.BENEFICIARY_LIMIT_REACHED);
    }

    @Test
    void parallelDeletesAllSucceedAndAuditOnce() throws Exception {
        BeneficiaryDto b = add(alice, otherCustomersAccount(), "x");
        UUID id = UUID.fromString(b.beneficiaryId());
        List<Object> outcomes = race(5, () -> { beneficiaries.delete(alice, id, "it-race"); return "ok"; });

        assertThat(outcomes).containsOnly("ok");
        int audits = db.owner.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_EVENTS WHERE ENTITY_ID = ? AND ACTION = 'BENEFICIARY_DELETE'",
                Integer.class, Ids.toBytes(id));
        assertThat(audits).isEqualTo(1);
    }

    // ------------------------------------------------------------------ database guards behind the service

    @Test
    void theTriggerFreezesDeletedRowsEvenForTheOwner() {
        BeneficiaryDto b = add(alice, otherCustomersAccount(), "x");
        byte[] id = Ids.toBytes(UUID.fromString(b.beneficiaryId()));
        assertThatThrownBy(() -> db.owner.update("UPDATE BENEFICIARIES SET NICKNAME = 'y' WHERE BENEFICIARY_ID = ?", id))
                .hasMessageContaining("ORA-20003");
        beneficiaries.delete(alice, UUID.fromString(b.beneficiaryId()), "it");
        assertThatThrownBy(() -> db.owner.update(
                "UPDATE BENEFICIARIES SET STATUS = 'ACTIVE', DELETED_AT = NULL, DELETED_BY_USER_ID = NULL WHERE BENEFICIARY_ID = ?", id))
                .hasMessageContaining("ORA-20003");
        assertThatThrownBy(() -> db.owner.update("DELETE FROM BENEFICIARIES WHERE BENEFICIARY_ID = ?", id))
                .hasMessageContaining("ORA-20003");
    }
}
