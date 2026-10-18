package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.CurrencyPolicy;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.events.EventEnvelope;
import com.enterprise.funds.transfer.persistence.AccountRepository;
import com.enterprise.funds.transfer.persistence.BeneficiaryRepository;
import com.enterprise.funds.transfer.persistence.CustomerRepository;
import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.persistence.Rows.AccountRow;
import com.enterprise.funds.transfer.persistence.Rows.BeneficiaryRow;
import com.enterprise.funds.transfer.web.dto.BeneficiaryDto;
import com.enterprise.funds.transfer.web.dto.BeneficiaryListDto;
import com.enterprise.funds.transfer.web.dto.CreateBeneficiaryRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Beneficiary use cases (docs/release-2-beneficiaries-contract.md, decisions B1-B8). Beneficiaries are owned by
 * the customer (B2); operators get 403 everywhere (B8). Transfers never read this table (B1).
 */
@Service
public class BeneficiaryService {

    static final int NICKNAME_MAX = 40;
    static final Duration ADD_WINDOW = Duration.ofHours(24);

    private final Clock clock;
    private final TransactionTemplate tx;
    private final BeneficiaryRepository beneficiaries;
    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final FundsProperties.Beneficiaries caps;

    public BeneficiaryService(Clock clock, TransactionTemplate postingTransaction, BeneficiaryRepository beneficiaries,
                              AccountRepository accounts, CustomerRepository customers, AuditService audit,
                              OutboxWriter outbox, FundsProperties props) {
        this.clock = clock;
        this.tx = postingTransaction;
        this.beneficiaries = beneficiaries;
        this.accounts = accounts;
        this.customers = customers;
        this.audit = audit;
        this.outbox = outbox;
        this.caps = props.beneficiaries();
    }

    public BeneficiaryListDto list(Actor actor) {
        requireCustomer(actor);
        return new BeneficiaryListDto(beneficiaries.listActive(actor.customerId()).stream()
                .map(BeneficiaryDto::from).toList());
    }

    /** Another customer's, a deleted and an unknown beneficiary are the same 404. */
    public BeneficiaryDto get(Actor actor, UUID beneficiaryId) {
        requireCustomer(actor);
        return beneficiaries.find(actor.customerId(), beneficiaryId)
                .filter(BeneficiaryRow::isActive)
                .map(BeneficiaryDto::from)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /**
     * Validation order is the contract's (first failure wins): schema and nickname (400), own account (422),
     * unknown or non-active account (422, indistinguishable, B4), then under the customer-row lock: already saved
     * (409 + Location, B3) and the caps (422, B5). The lock is the same first lock posting takes (A1), so
     * concurrent adds for one customer cannot exceed a cap or both pass the duplicate check.
     */
    public BeneficiaryDto create(Actor actor, CreateBeneficiaryRequest request, String correlationId) {
        requireCustomer(actor);
        String nickname = normalizeNickname(request.nickname());
        try {
            AccountRow account = accounts.findByNumber(request.accountId()).orElse(null);
            if (account != null && actor.isCustomerOf(account.customerId())) {
                throw new ApiException(ErrorCode.BENEFICIARY_OWN_ACCOUNT);
            }
            if (account == null || !account.isActive() || !CurrencyPolicy.isSupported(account.currency())) {
                throw new ApiException(ErrorCode.ACCOUNT_INACTIVE);
            }
            return tx.execute(status -> createLocked(actor, account, nickname, correlationId));
        } catch (DuplicateKeyException e) {
            // UX_BENEF_ACTIVE. Unreachable while every add takes the customer lock; kept so a writer that skips the
            // lock still gets the documented answer instead of a 500.
            AccountRow account = accounts.findByNumber(request.accountId()).orElseThrow();
            throw rejected(actor, alreadyExists(beneficiaries.findActiveByAccount(actor.customerId(), account.id())
                    .orElseThrow(() -> e)), correlationId);
        } catch (ApiException e) {
            throw rejected(actor, e, correlationId);
        }
    }

    /** Idempotent soft delete (B7): an already deleted beneficiary of the caller's customer is 204 again. */
    public void delete(Actor actor, UUID beneficiaryId, String correlationId) {
        requireCustomer(actor);
        BeneficiaryRow row = beneficiaries.find(actor.customerId(), beneficiaryId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!row.isActive()) {
            return;
        }
        tx.executeWithoutResult(status -> {
            // A false result means a concurrent delete won; the outcome is the same, and that one wrote the audit row.
            if (beneficiaries.markDeleted(actor.customerId(), beneficiaryId, actor.userId(), OffsetDateTime.now(clock))) {
                audit.record(actor, "BENEFICIARY_DELETE", "BENEFICIARY", beneficiaryId, "SUCCESS", correlationId, null);
                outbox.writeBeneficiary(EventEnvelope.BENEFICIARY_DELETED, row);
            }
        });
    }

    private BeneficiaryDto createLocked(Actor actor, AccountRow account, String nickname, String correlationId) {
        customers.lock(actor.customerId());
        var existing = beneficiaries.findActiveByAccount(actor.customerId(), account.id());
        if (existing.isPresent()) {
            throw alreadyExists(existing.get());
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (beneficiaries.countActive(actor.customerId()) >= caps.maxActive()
                || beneficiaries.countCreatedSince(actor.customerId(), now.minus(ADD_WINDOW)) >= caps.maxAddsPerDay()) {
            throw new ApiException(ErrorCode.BENEFICIARY_LIMIT_REACHED);
        }
        UUID id = Ids.newId();
        beneficiaries.insert(id, actor.customerId(), account.id(), nickname, actor.userId(), now);
        // DETAIL stays empty: the nickname is customer free text and never goes into the audit trail.
        audit.record(actor, "BENEFICIARY_ADD", "BENEFICIARY", id, "SUCCESS", correlationId, null);
        BeneficiaryRow created = beneficiaries.find(actor.customerId(), id).orElseThrow();
        // Same transaction as the insert: the event exists if and only if the beneficiary does (a "was this you?"
        // security notice, E7).
        outbox.writeBeneficiary(EventEnvelope.BENEFICIARY_ADDED, created);
        return BeneficiaryDto.from(created);
    }

    /**
     * Business rejections are audited against the customer, since no beneficiary exists, so repeated probing for
     * active account numbers (B4, B5) leaves a trail. Written after the rollback, and never masks the rejection.
     */
    private ApiException rejected(Actor actor, ApiException e, String correlationId) {
        audit.recordQuietly(actor, "BENEFICIARY_ADD", "CUSTOMER", actor.customerId(), "FAILURE", correlationId,
                e.code().name());
        return e;
    }

    private static ApiException alreadyExists(BeneficiaryRow existing) {
        return new ApiException(ErrorCode.BENEFICIARY_ALREADY_EXISTS)
                .header("Location", "/api/v1/beneficiaries/" + existing.id());
    }

    private static void requireCustomer(Actor actor) {
        if (actor.isOperator()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * Trims, then requires 1-40 characters (code points, matching VARCHAR2(40 CHAR)) and no control characters
     * (C0, DEL and C1, matching CK_BENEF_NICKNAME's [[:cntrl:]]).
     */
    static String normalizeNickname(String raw) {
        String nickname = raw.strip();
        if (nickname.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST).detail("nickname", "required");
        }
        if (nickname.codePointCount(0, nickname.length()) > NICKNAME_MAX) {
            throw new ApiException(ErrorCode.INVALID_REQUEST).detail("nickname", "too long");
        }
        if (nickname.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST).detail("nickname", "invalid format");
        }
        return nickname;
    }
}
