package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.CurrencyPolicy;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.domain.RiskDecision;
import com.enterprise.funds.transfer.domain.Role;
import com.enterprise.funds.transfer.domain.TransferStateMachine;
import com.enterprise.funds.transfer.domain.TransferStatus;
import com.enterprise.funds.transfer.persistence.AccountRepository;
import com.enterprise.funds.transfer.persistence.Ids;
import com.enterprise.funds.transfer.persistence.RiskRepository;
import com.enterprise.funds.transfer.persistence.Rows.AccountRow;
import com.enterprise.funds.transfer.persistence.Rows.TransferView;
import com.enterprise.funds.transfer.persistence.TransferRepository;
import com.enterprise.funds.transfer.web.dto.CreateTransferRequest;
import com.enterprise.funds.transfer.web.dto.TransferDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transfer use cases: create (idempotent, synchronous posting), read, cancel.
 *
 * Create is three steps, following docs/milestone-2-erd.md section 11:
 * (1) claim the Idempotency-Key in its own short transaction; (2) validate and post in ONE transaction that
 * also completes the idempotency record; (3) map failures: a 422 is stored and replayed, anything else releases
 * the attempt so a retry can proceed.
 */
@Service
public class TransferService {

    private static final Logger LOG = LoggerFactory.getLogger(TransferService.class);
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Outcome of a successful create. replayed is true when answered from the stored original. */
    public record CreateOutcome(TransferDto transfer, UUID transferId, boolean replayed) {}

    private record Command(String source, String destination, BigDecimal amount, String currency, String reference) {}

    private record TxResult(TransferDto dto, UUID transferId, ApiException rejection) {}

    private final Clock clock;
    private final TransactionTemplate tx;
    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final RiskRepository riskRepo;
    private final PostingService posting;
    private final LimitChecker limits;
    private final RiskEngine riskEngine;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;

    public TransferService(Clock clock, TransactionTemplate postingTransaction, IdempotencyService idempotency,
                           RequestHasher hasher, AccountRepository accounts, TransferRepository transfers,
                           RiskRepository riskRepo, PostingService posting, LimitChecker limits,
                           RiskEngine riskEngine, AuditService audit, OutboxWriter outbox, ObjectMapper mapper) {
        this.clock = clock;
        this.tx = postingTransaction;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.accounts = accounts;
        this.transfers = transfers;
        this.riskRepo = riskRepo;
        this.posting = posting;
        this.limits = limits;
        this.riskEngine = riskEngine;
        this.audit = audit;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ create

    public CreateOutcome create(Actor actor, String rawKey, CreateTransferRequest request, String correlationId) {
        if (actor.role() != Role.CUSTOMER) {
            throw new ApiException(ErrorCode.FORBIDDEN); // operators may read and cancel, never create
        }
        UUID key = parseKey(rawKey);
        Command command = validate(request);
        byte[] hash = hasher.hash(command.source(), command.destination(), command.amount(),
                command.currency(), command.reference());

        IdempotencyService.Begin begin = idempotency.begin(actor.userId(), key, hash);
        if (begin instanceof IdempotencyService.Begin.Replay replay) {
            return replayOutcome(replay);
        }
        IdempotencyService.Begin.Proceed attempt = (IdempotencyService.Begin.Proceed) begin;

        TxResult result;
        try {
            result = tx.execute(status -> createInTransaction(actor, attempt, command, correlationId));
        } catch (ApiException e) {
            throw onBusinessFailure(actor, attempt, e, correlationId);
        } catch (FencedOutException e) {
            throw idempotency.inProgress(); // a retry took over; this attempt rolled back and posted nothing
        } catch (RuntimeException e) {
            LOG.error("Transfer creation failed (correlationId={})", correlationId, e);
            abortQuietly(attempt);
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        if (result.rejection() != null) {
            throw result.rejection(); // already stored and committed with its REJECTED transfer
        }
        return new CreateOutcome(result.dto(), result.transferId(), false);
    }

    private TxResult createInTransaction(Actor actor, IdempotencyService.Begin.Proceed attempt, Command cmd,
                                         String correlationId) {
        idempotency.requireOwnership(attempt); // first lock: superseded attempts stop here
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Validation steps 2-7 (contract order). Reads only; the authoritative checks run under locks below.
        AccountRow source = accounts.findByNumber(cmd.source())
                .filter(a -> actor.isCustomerOf(a.customerId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); // absent and not-yours look identical
        AccountRow destination = accounts.findByNumber(cmd.destination()).orElse(null);
        if (destination == null || !destination.isActive() || !source.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_INACTIVE); // unknown destination = inactive: no probing
        }
        if (source.id().equals(destination.id())) {
            throw new ApiException(ErrorCode.SAME_ACCOUNT);
        }
        if (cmd.amount().signum() <= 0) {
            throw new ApiException(ErrorCode.AMOUNT_NOT_POSITIVE);
        }
        if (!cmd.currency().equals(source.currency()) || !cmd.currency().equals(destination.currency())) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH);
        }
        limits.requireWithinLimits(source, cmd.amount(), now); // advisory fast-fail

        RiskDecision risk = riskEngine.assess(cmd.amount(), cmd.currency());
        UUID transferId = Ids.newId();
        return switch (risk.outcome()) {
            case REJECT -> rejectByRisk(actor, attempt, cmd, source, destination, transferId, risk, now, correlationId);
            case REVIEW -> holdForReview(actor, attempt, cmd, source, destination, transferId, risk, now, correlationId);
            case APPROVE -> post(actor, attempt, cmd, source, destination, transferId, risk, now, correlationId);
        };
    }

    private TxResult post(Actor actor, IdempotencyService.Begin.Proceed attempt, Command cmd, AccountRow source,
                          AccountRow destination, UUID transferId, RiskDecision risk, OffsetDateTime now,
                          String correlationId) {
        PostingService.Locked locked = posting.lock(source, destination);
        posting.enforce(locked, source, cmd.amount(), now); // limits, then funds, under the locks
        TransferStatus status = TransferStateMachine.walk(TransferStatus.RECEIVED,
                TransferStatus.VALIDATING, TransferStatus.PROCESSING, TransferStatus.COMPLETED);
        insertTransfer(actor, cmd, source, destination, transferId, status, now);
        riskRepo.insert(transferId, risk, now);
        posting.apply(locked, transferId, source, destination, cmd.amount(), now);
        return finishCreated(actor, attempt, transferId, "TransferCompleted", correlationId);
    }

    private TxResult holdForReview(Actor actor, IdempotencyService.Begin.Proceed attempt, Command cmd,
                                   AccountRow source, AccountRow destination, UUID transferId, RiskDecision risk,
                                   OffsetDateTime now, String correlationId) {
        // No funds move and none are reserved (V1 has no holds); funds and limits are re-checked when it posts.
        TransferStatus status = TransferStateMachine.walk(TransferStatus.RECEIVED,
                TransferStatus.VALIDATING, TransferStatus.PENDING_REVIEW);
        insertTransfer(actor, cmd, source, destination, transferId, status, now);
        riskRepo.insert(transferId, risk, now);
        return finishCreated(actor, attempt, transferId, "TransferPendingReview", correlationId);
    }

    private TxResult rejectByRisk(Actor actor, IdempotencyService.Begin.Proceed attempt, Command cmd,
                                  AccountRow source, AccountRow destination, UUID transferId, RiskDecision risk,
                                  OffsetDateTime now, String correlationId) {
        TransferStatus status = TransferStateMachine.walk(TransferStatus.RECEIVED,
                TransferStatus.VALIDATING, TransferStatus.REJECTED);
        insertTransfer(actor, cmd, source, destination, transferId, status, now);
        riskRepo.insert(transferId, risk, now);
        ApiException rejection = new ApiException(ErrorCode.TRANSFER_REJECTED); // generic: no risk detail
        if (!idempotency.complete(attempt, transferId, rejection.status(), errorJson(rejection))) {
            throw new FencedOutException();
        }
        TransferDto dto = TransferDto.from(transfers.findView(transferId).orElseThrow());
        audit.record(actor, "TRANSFER_CREATE", "TRANSFER", transferId, "DENIED", correlationId, "REJECTED");
        outbox.write("TransferRejected", transferId, dto);
        return new TxResult(null, transferId, rejection);
    }

    private void insertTransfer(Actor actor, Command cmd, AccountRow source, AccountRow destination,
                                UUID transferId, TransferStatus status, OffsetDateTime now) {
        transfers.insert(transferId, source.id(), destination.id(), cmd.amount(), cmd.currency(), status,
                cmd.reference(), actor.userId(), null, now);
    }

    private TxResult finishCreated(Actor actor, IdempotencyService.Begin.Proceed attempt, UUID transferId,
                                   String eventType, String correlationId) {
        TransferDto dto = TransferDto.from(transfers.findView(transferId).orElseThrow());
        if (!idempotency.complete(attempt, transferId, 201, toJson(dto))) {
            throw new FencedOutException(); // rolls back the whole posting
        }
        audit.record(actor, "TRANSFER_CREATE", "TRANSFER", transferId, "SUCCESS", correlationId, dto.status());
        outbox.write(eventType, transferId, dto);
        return new TxResult(dto, transferId, null);
    }

    /** After the posting transaction rolled back on a documented error. */
    private ApiException onBusinessFailure(Actor actor, IdempotencyService.Begin.Proceed attempt, ApiException e,
                                           String correlationId) {
        if (e.isStorableOutcome()) {
            // 422: store it so a retry replays it instead of re-evaluating (contract, createTransfer)
            if (!idempotency.complete(attempt, null, e.status(), errorJson(e))) {
                return idempotency.inProgress();
            }
            audit.recordQuietly(actor, "TRANSFER_CREATE", "IDEMPOTENCY_KEY", attempt.recordId(), "DENIED",
                    correlationId, e.code().name());
            return e;
        }
        abortQuietly(attempt); // 404 and friends are not stored; the key stays free for a corrected retry
        return e;
    }

    private void abortQuietly(IdempotencyService.Begin.Proceed attempt) {
        try {
            idempotency.abort(attempt);
        } catch (RuntimeException e) {
            LOG.warn("Could not release idempotency attempt {}; the lease will expire on its own", attempt.recordId(), e);
        }
    }

    private CreateOutcome replayOutcome(IdempotencyService.Begin.Replay replay) {
        if (replay.status() == 201) {
            try {
                return new CreateOutcome(mapper.readValue(replay.body(), TransferDto.class), replay.transferId(), true);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Stored idempotent response is unreadable", e);
            }
        }
        throw storedError(replay.body()).header("Idempotent-Replayed", "true");
    }

    // ------------------------------------------------------------------ read and cancel

    public TransferView get(Actor actor, UUID transferId, String correlationId) {
        TransferView view = transfers.findView(transferId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!TransferAccess.canView(actor, view)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        if (actor.isOperator()) {
            audit.record(actor, "OPERATOR_READ", "TRANSFER", transferId, "SUCCESS", correlationId, null);
        }
        return view;
    }

    public TransferView cancel(Actor actor, UUID transferId, String correlationId) {
        return tx.execute(status -> {
            // Locks the transfer row, so a cancel racing a review approval resolves to exactly one outcome.
            TransferView view = transfers.lockView(transferId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (!TransferAccess.canView(actor, view)) {
                throw new ApiException(ErrorCode.NOT_FOUND);
            }
            if (!TransferAccess.canCancel(actor, view)) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (view.status() == TransferStatus.CANCELLED) {
                return view; // cancelling twice is not an error
            }
            if (!view.status().isCancellable()) {
                throw new ApiException(ErrorCode.TRANSFER_NOT_CANCELLABLE);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            transfers.updateStatus(transferId, view.status(),
                    TransferStateMachine.require(view.status(), TransferStatus.CANCELLED), now);
            TransferView cancelled = transfers.findView(transferId).orElseThrow();
            audit.record(actor, "TRANSFER_CANCEL", "TRANSFER", transferId, "SUCCESS", correlationId, null);
            outbox.write("TransferCancelled", transferId, TransferDto.from(cancelled));
            return cancelled;
        });
    }

    // ------------------------------------------------------------------ validation and JSON helpers

    private static UUID parseKey(String raw) {
        if (raw == null || !UUID_FORMAT.matcher(raw).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST).detail("Idempotency-Key", "must be a UUID");
        }
        return UUID.fromString(raw);
    }

    private static Command validate(CreateTransferRequest r) {
        if (!CurrencyPolicy.isSupported(r.currency())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST).detail("currency", "unsupported currency");
        }
        BigDecimal amount = new BigDecimal(r.amount()); // format already checked by bean validation
        CurrencyPolicy.requireScale(r.currency(), amount);
        return new Command(r.sourceAccountId(), r.destinationAccountId(), amount, r.currency(), r.reference());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise response", e);
        }
    }

    /** The stored form of an error: code, message, details. correlationId and timestamp are added per request. */
    private String errorJson(ApiException e) {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", e.code().name());
        node.put("message", e.getMessage());
        if (!e.details().isEmpty()) {
            ArrayNode details = node.putArray("details");
            e.details().forEach(d -> details.addObject().put("field", d.field()).put("issue", d.issue()));
        }
        return node.toString();
    }

    private ApiException storedError(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            ApiException e = new ApiException(ErrorCode.valueOf(node.get("code").asText()), node.get("message").asText());
            if (node.has("details")) {
                node.get("details").forEach(d -> e.detail(d.get("field").asText(), d.get("issue").asText()));
            }
            return e;
        } catch (JsonProcessingException | RuntimeException ex) {
            LOG.error("Stored idempotent error response is unreadable", ex);
            return new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
