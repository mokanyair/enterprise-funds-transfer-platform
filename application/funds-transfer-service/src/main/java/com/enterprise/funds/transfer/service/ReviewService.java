package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.domain.TransferStateMachine;
import com.enterprise.funds.transfer.domain.TransferStatus;
import com.enterprise.funds.transfer.persistence.AccountRepository;
import com.enterprise.funds.transfer.persistence.Rows.AccountRow;
import com.enterprise.funds.transfer.persistence.Rows.TransferView;
import com.enterprise.funds.transfer.persistence.TransferRepository;
import com.enterprise.funds.transfer.web.dto.TransferDto;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual-review outcome for PENDING_REVIEW transfers. The API contract has no endpoint for this (V1), so this
 * service is not exposed over HTTP; it exists so the state machine has its exit and so the post-review path
 * shares PostingService (and therefore the same limit and funds checks) with the direct path.
 */
@Service
public class ReviewService {

    private final TransactionTemplate tx;
    private final Clock clock;
    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final PostingService posting;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public ReviewService(TransactionTemplate postingTransaction, Clock clock, TransferRepository transfers,
                         AccountRepository accounts, PostingService posting, AuditService audit, OutboxWriter outbox) {
        this.tx = postingTransaction;
        this.clock = clock;
        this.transfers = transfers;
        this.accounts = accounts;
        this.posting = posting;
        this.audit = audit;
        this.outbox = outbox;
    }

    /**
     * Approves and posts. Limits and funds are checked again under the posting locks, because nothing was reserved
     * while the transfer waited. If they no longer hold, the transfer is REJECTED (PENDING_REVIEW -> REJECTED).
     */
    public TransferView approve(Actor operator, UUID transferId, String correlationId) {
        requireOperator(operator);
        return tx.execute(status -> {
            TransferView view = lockPending(transferId); // lock order: transfer row, customer, accounts
            OffsetDateTime now = OffsetDateTime.now(clock);
            AccountRow source = accounts.findById(view.sourceAccountId()).orElseThrow();
            AccountRow destination = accounts.findById(view.destinationAccountId()).orElseThrow();
            try {
                var locked = posting.lock(source, destination);
                posting.enforce(locked, source, view.amount(), now);
                TransferStateMachine.walk(TransferStatus.PENDING_REVIEW, TransferStatus.PROCESSING, TransferStatus.COMPLETED);
                transfers.updateStatus(transferId, TransferStatus.PENDING_REVIEW, TransferStatus.PROCESSING, now);
                transfers.updateStatus(transferId, TransferStatus.PROCESSING, TransferStatus.COMPLETED, now);
                posting.apply(locked, transferId, source, destination, view.amount(), now);
                return finish(operator, transferId, "TransferCompleted", "REVIEW_APPROVE", "SUCCESS", correlationId);
            } catch (ApiException e) {
                if (e.status() != 422) {
                    throw e;
                }
                transfers.updateStatus(transferId, TransferStatus.PENDING_REVIEW,
                        TransferStateMachine.require(TransferStatus.PENDING_REVIEW, TransferStatus.REJECTED), now);
                return finish(operator, transferId, "TransferRejected", "REVIEW_APPROVE", "DENIED", correlationId);
            }
        });
    }

    public TransferView reject(Actor operator, UUID transferId, String correlationId) {
        requireOperator(operator);
        return tx.execute(status -> {
            TransferView view = lockPending(transferId);
            transfers.updateStatus(transferId, view.status(),
                    TransferStateMachine.require(view.status(), TransferStatus.REJECTED), OffsetDateTime.now(clock));
            return finish(operator, transferId, "TransferRejected", "REVIEW_REJECT", "SUCCESS", correlationId);
        });
    }

    private TransferView lockPending(UUID transferId) {
        TransferView view = transfers.lockView(transferId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (view.status() != TransferStatus.PENDING_REVIEW) {
            throw new ApiException(ErrorCode.TRANSFER_NOT_CANCELLABLE, "The transfer is not awaiting review.");
        }
        return view;
    }

    private TransferView finish(Actor operator, UUID transferId, String eventType, String action, String outcome,
                                String correlationId) {
        TransferView after = transfers.findView(transferId).orElseThrow();
        audit.record(operator, action, "TRANSFER", transferId, outcome, correlationId, after.status().name());
        outbox.write(eventType, transferId, TransferDto.from(after));
        return after;
    }

    private static void requireOperator(Actor actor) {
        if (!actor.isOperator()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
}
