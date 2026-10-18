package com.enterprise.funds.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.funds.transfer.TestProps;
import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.domain.Role;
import com.enterprise.funds.transfer.persistence.AccountRepository;
import com.enterprise.funds.transfer.persistence.BeneficiaryRepository;
import com.enterprise.funds.transfer.persistence.CustomerRepository;
import com.enterprise.funds.transfer.persistence.Rows.AccountRow;
import com.enterprise.funds.transfer.persistence.Rows.BeneficiaryRow;
import com.enterprise.funds.transfer.web.dto.BeneficiaryDto;
import com.enterprise.funds.transfer.web.dto.CreateBeneficiaryRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decision logic of BeneficiaryService against mocked repositories: validation order, the B1-B8 rules, audit, and
 * what happens inside vs outside the transaction. Concurrency under the real customer lock is in BeneficiaryIT.
 */
class BeneficiaryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00.123456Z");

    /** Records commits and rollbacks; no resources. */
    static final class RecordingTxManager extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }

    private final BeneficiaryRepository beneficiaries = mock(BeneficiaryRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final RecordingTxManager txManager = new RecordingTxManager();

    private final UUID customerId = UUID.randomUUID();
    private final Actor alice = new Actor(UUID.randomUUID(), Role.CUSTOMER, customerId);
    private final Actor operator = new Actor(UUID.randomUUID(), Role.OPERATOR, null);
    private final AccountRow theirs = new AccountRow(UUID.randomUUID(), "ACC002", UUID.randomUUID(), "USD", "ACTIVE");
    private final AccountRow mine = new AccountRow(UUID.randomUUID(), "ACC001", customerId, "USD", "ACTIVE");

    private BeneficiaryService service;

    @BeforeEach
    void setUp() {
        service = service(new FundsProperties.Beneficiaries(50, 20));
        when(accounts.findByNumber("ACC002")).thenReturn(Optional.of(theirs));
        when(accounts.findByNumber("ACC001")).thenReturn(Optional.of(mine));
        when(accounts.findByNumber("NOPE")).thenReturn(Optional.empty());
        when(beneficiaries.findActiveByAccount(any(), any())).thenReturn(Optional.empty());
        when(beneficiaries.find(eq(customerId), any())).thenAnswer(inv -> Optional.of(
                row(inv.getArgument(1), theirs, "Rent - landlord", "ACTIVE")));
    }

    private BeneficiaryService service(FundsProperties.Beneficiaries caps) {
        FundsProperties props = TestProps.props(Duration.ofDays(7), Duration.ofSeconds(60), Duration.ofDays(90),
                new BigDecimal("10000.00"), null, caps);
        return new BeneficiaryService(Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(txManager),
                beneficiaries, accounts, customers, audit, props);
    }

    private BeneficiaryRow row(UUID id, AccountRow account, String nickname, String status) {
        return new BeneficiaryRow(id, customerId, account.id(), account.number(), nickname, status,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private static CreateBeneficiaryRequest req(String accountId, String nickname) {
        return new CreateBeneficiaryRequest(accountId, nickname);
    }

    private static ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e.code();
        }
        throw new AssertionError("expected an ApiException");
    }

    // ------------------------------------------------------------------ operators (B8)

    @Test
    void operatorsAreForbiddenEverywhereAndTouchNothing() {
        UUID id = UUID.randomUUID();
        assertThat(codeOf(() -> service.list(operator))).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(() -> service.get(operator, id))).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(() -> service.create(operator, req("ACC002", "x"), "c"))).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(() -> service.delete(operator, id, "c"))).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(beneficiaries, customers, audit);
    }

    // ------------------------------------------------------------------ create: happy path

    @Test
    void createTrimsTheNicknameInsertsUnderTheCustomerLockAndAuditsWithoutTheNickname() {
        BeneficiaryDto dto = service.create(alice, req("ACC002", "  Rent - landlord  "), "corr-1");

        var order = inOrder(customers, beneficiaries, audit);
        order.verify(customers).lock(customerId);
        order.verify(beneficiaries).findActiveByAccount(customerId, theirs.id());
        order.verify(beneficiaries).insert(any(), eq(customerId), eq(theirs.id()), eq("Rent - landlord"),
                eq(alice.userId()), eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        order.verify(audit).record(eq(alice), eq("BENEFICIARY_ADD"), eq("BENEFICIARY"), any(), eq("SUCCESS"),
                eq("corr-1"), isNull());
        assertThat(dto.accountId()).isEqualTo("ACC002");
        assertThat(dto.nickname()).isEqualTo("Rent - landlord");
        assertThat(txManager.commits).isEqualTo(1);
        verify(audit, never()).recordQuietly(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theDailyCapCountsAddsInTheLast24Hours() {
        service.create(alice, req("ACC002", "x"), "c");
        verify(beneficiaries).countCreatedSince(customerId, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(24));
    }

    @Test
    void fortyCodePointsAreAcceptedEvenWhenTheyNeedSurrogatePairs() {
        String forty = "🏠".repeat(40); // 40 house emoji = 80 UTF-16 units
        service.create(alice, req("ACC002", forty), "c");
        verify(beneficiaries).insert(any(), any(), any(), eq(forty), any(), any());
    }

    // ------------------------------------------------------------------ create: validation order

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t \n"})
    void blankNicknamesAreRejectedBeforeAnyLookup(String nickname) {
        assertThatThrownBy(() -> service.create(alice, req("ACC002", nickname), "c"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(e.details()).extracting(ApiException.Detail::field).containsExactly("nickname");
                });
        verifyNoInteractions(accounts, customers, audit);
    }

    @Test
    void fortyOneCharactersAreTooLong() {
        assertThat(codeOf(() -> service.create(alice, req("ACC002", "x".repeat(41)), "c")))
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\nb", "a\u001Fb", "a\u007Fb", "a\u0085b", "a\u009Fb"})
    void controlCharactersInsideTheNicknameAreRejected(String nickname) {
        assertThat(codeOf(() -> service.create(alice, req("ACC002", nickname), "c"))).isEqualTo(ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(customers);
    }

    @Test
    void ownAccountIsRejectedBeforeTheActiveCheckEvenWhenFrozen() {
        when(accounts.findByNumber("ACC001")).thenReturn(Optional.of(
                new AccountRow(mine.id(), "ACC001", customerId, "USD", "FROZEN")));
        assertThat(codeOf(() -> service.create(alice, req("ACC001", "x"), "c"))).isEqualTo(ErrorCode.BENEFICIARY_OWN_ACCOUNT);
        verifyNoInteractions(customers);
    }

    @Test
    void unknownAndInactiveAccountsAreIndistinguishable() {
        when(accounts.findByNumber("FRZ")).thenReturn(Optional.of(
                new AccountRow(UUID.randomUUID(), "FRZ", UUID.randomUUID(), "USD", "FROZEN")));
        when(accounts.findByNumber("CLS")).thenReturn(Optional.of(
                new AccountRow(UUID.randomUUID(), "CLS", UUID.randomUUID(), "USD", "CLOSED")));
        List<ApiException> errors = List.of("NOPE", "FRZ", "CLS").stream().map(n -> {
            try {
                service.create(alice, req(n, "x"), "c");
                throw new AssertionError("expected rejection for " + n);
            } catch (ApiException e) {
                return e;
            }
        }).toList();
        assertThat(errors).allSatisfy(e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_INACTIVE);
            assertThat(e.getMessage()).isEqualTo(errors.get(0).getMessage());
            assertThat(e.headers()).isEmpty();
        });
        verifyNoInteractions(customers);
    }

    @Test
    void anAlreadySavedAccountIs409WithTheExistingLocationAndRollsBack() {
        UUID existing = UUID.randomUUID();
        when(beneficiaries.findActiveByAccount(customerId, theirs.id()))
                .thenReturn(Optional.of(row(existing, theirs, "Old", "ACTIVE")));

        assertThatThrownBy(() -> service.create(alice, req("ACC002", "New"), "c"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.BENEFICIARY_ALREADY_EXISTS);
                    assertThat(e.headers()).containsEntry("Location", "/api/v1/beneficiaries/" + existing);
                });
        verify(customers).lock(customerId); // checked under the lock, not before it
        verify(beneficiaries, never()).insert(any(), any(), any(), any(), any(), any());
        assertThat(txManager.rollbacks).isEqualTo(1);
    }

    @Test
    void theActiveCapIsEnforced() {
        when(beneficiaries.countActive(customerId)).thenReturn(50);
        assertThat(codeOf(() -> service.create(alice, req("ACC002", "x"), "c"))).isEqualTo(ErrorCode.BENEFICIARY_LIMIT_REACHED);
        verify(beneficiaries, never()).insert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theDailyAddCapIsEnforcedAndUsesConfiguredValues() {
        BeneficiaryService strict = service(new FundsProperties.Beneficiaries(50, 3));
        when(beneficiaries.countActive(customerId)).thenReturn(0);
        when(beneficiaries.countCreatedSince(eq(customerId), any())).thenReturn(3);
        assertThat(codeOf(() -> strict.create(alice, req("ACC002", "x"), "c"))).isEqualTo(ErrorCode.BENEFICIARY_LIMIT_REACHED);
    }

    @Test
    void justUnderBothCapsSucceeds() {
        when(beneficiaries.countActive(customerId)).thenReturn(49);
        when(beneficiaries.countCreatedSince(eq(customerId), any())).thenReturn(19);
        service.create(alice, req("ACC002", "x"), "c");
        verify(beneficiaries).insert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aUniqueIndexViolationStillAnswers409WithLocation() {
        UUID existing = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new DuplicateKeyException("UX_BENEF_ACTIVE"))
                .when(beneficiaries).insert(any(), any(), any(), any(), any(), any());
        when(beneficiaries.findActiveByAccount(customerId, theirs.id()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(row(existing, theirs, "Old", "ACTIVE")));

        assertThatThrownBy(() -> service.create(alice, req("ACC002", "x"), "c"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.BENEFICIARY_ALREADY_EXISTS);
                    assertThat(e.headers()).containsEntry("Location", "/api/v1/beneficiaries/" + existing);
                });
        verify(audit).recordQuietly(alice, "BENEFICIARY_ADD", "CUSTOMER", customerId, "FAILURE", "c",
                "BENEFICIARY_ALREADY_EXISTS");
    }

    @Test
    void businessRejectionsAreAuditedAgainstTheCustomerWithTheCodeOnly() {
        codeOf(() -> service.create(alice, req("NOPE", "secret nickname"), "corr-9"));
        verify(audit).recordQuietly(alice, "BENEFICIARY_ADD", "CUSTOMER", customerId, "FAILURE", "corr-9",
                "ACCOUNT_INACTIVE");
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void malformedRequestsAreNotAuditedAsBusinessRejections() {
        codeOf(() -> service.create(alice, req("ACC002", " "), "c"));
        verifyNoInteractions(audit);
    }

    // ------------------------------------------------------------------ list and get

    @Test
    void listMapsActiveRowsOnly() {
        when(beneficiaries.listActive(customerId)).thenReturn(List.of(row(UUID.randomUUID(), theirs, "A", "ACTIVE")));
        assertThat(service.list(alice).beneficiaries()).singleElement()
                .satisfies(b -> assertThat(b.accountId()).isEqualTo("ACC002"));
    }

    @Test
    void createdAtIsTruncatedToMillis() {
        when(beneficiaries.listActive(customerId)).thenReturn(List.of(row(UUID.randomUUID(), theirs, "A", "ACTIVE")));
        assertThat(service.list(alice).beneficiaries().get(0).createdAt()).isEqualTo(Instant.parse("2026-10-18T09:00:00.123Z"));
    }

    @Test
    void getOfADeletedOrUnknownBeneficiaryIs404() {
        UUID deleted = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(beneficiaries.find(customerId, deleted)).thenReturn(Optional.of(row(deleted, theirs, "x", "DELETED")));
        when(beneficiaries.find(customerId, unknown)).thenReturn(Optional.empty());
        assertThat(codeOf(() -> service.get(alice, deleted))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> service.get(alice, unknown))).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void lookupsAreAlwaysScopedToTheCallersCustomer() {
        UUID id = UUID.randomUUID();
        service.get(alice, id);
        verify(beneficiaries).find(customerId, id);
    }

    // ------------------------------------------------------------------ delete (B7)

    @Test
    void deleteSoftDeletesAndAudits() {
        UUID id = UUID.randomUUID();
        when(beneficiaries.markDeleted(eq(customerId), eq(id), eq(alice.userId()), any())).thenReturn(true);
        service.delete(alice, id, "corr-d");
        verify(audit).record(alice, "BENEFICIARY_DELETE", "BENEFICIARY", id, "SUCCESS", "corr-d", null);
        assertThat(txManager.commits).isEqualTo(1);
    }

    @Test
    void deletingTwiceIsANoOpWithoutASecondAuditRow() {
        UUID id = UUID.randomUUID();
        when(beneficiaries.find(customerId, id)).thenReturn(Optional.of(row(id, theirs, "x", "DELETED")));
        service.delete(alice, id, "c");
        verify(beneficiaries, never()).markDeleted(any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void losingADeleteRaceIsStillSuccessButOnlyTheWinnerAudits() {
        UUID id = UUID.randomUUID();
        when(beneficiaries.markDeleted(any(), any(), any(), any())).thenReturn(false);
        service.delete(alice, id, "c");
        verifyNoInteractions(audit);
    }

    @Test
    void deletingAnUnknownOrForeignBeneficiaryIs404() {
        UUID id = UUID.randomUUID();
        when(beneficiaries.find(customerId, id)).thenReturn(Optional.empty());
        assertThat(codeOf(() -> service.delete(alice, id, "c"))).isEqualTo(ErrorCode.NOT_FOUND);
        verify(beneficiaries, never()).markDeleted(any(), any(), any(), any());
    }

    @Test
    void nicknameNormalizationIsTrimThenValidate() {
        assertThat(BeneficiaryService.normalizeNickname("  a b  ")).isEqualTo("a b");
        assertThatThrownBy(() -> BeneficiaryService.normalizeNickname(" " + "x".repeat(41) + " "))
                .isInstanceOf(ApiException.class);
        assertThat(BeneficiaryService.normalizeNickname(" " + "x".repeat(40) + " ")).hasSize(40);
    }
}
