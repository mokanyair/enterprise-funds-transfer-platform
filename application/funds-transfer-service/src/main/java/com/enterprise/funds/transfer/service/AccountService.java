package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.domain.TransferStatus;
import com.enterprise.funds.transfer.persistence.AccountRepository;
import com.enterprise.funds.transfer.persistence.Rows.AccountRow;
import com.enterprise.funds.transfer.persistence.TransferRepository;
import com.enterprise.funds.transfer.web.dto.BalanceDto;
import com.enterprise.funds.transfer.web.dto.TransferDto;
import com.enterprise.funds.transfer.web.dto.TransferPageDto;
import com.enterprise.funds.transfer.domain.CurrencyPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/** Account-scoped reads. An account the caller may not see is indistinguishable from one that does not exist. */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final AuditService audit;
    private final Clock clock;

    public AccountService(AccountRepository accounts, TransferRepository transfers, AuditService audit, Clock clock) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.audit = audit;
        this.clock = clock;
    }

    public BalanceDto balance(Actor actor, String accountNumber, String correlationId) {
        AccountRow account = visibleAccount(actor, accountNumber);
        var balance = accounts.findBalance(account.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        auditOperatorRead(actor, account, "OPERATOR_READ_BALANCE", correlationId);
        return new BalanceDto(account.number(), account.currency(), scale4(balance.available()),
                scale4(balance.ledger()), Instant.now(clock).truncatedTo(ChronoUnit.MILLIS));
    }

    public TransferPageDto history(Actor actor, String accountNumber, int page, int size, TransferStatus status,
                                   String correlationId) {
        AccountRow account = visibleAccount(actor, accountNumber);
        var items = transfers.listForAccount(account.id(), status, page * size, size).stream()
                .map(TransferDto::from).toList();
        long total = transfers.countForAccount(account.id(), status);
        auditOperatorRead(actor, account, "OPERATOR_READ_HISTORY", correlationId);
        return new TransferPageDto(items, page, size, total, (int) ((total + size - 1) / size));
    }

    private AccountRow visibleAccount(Actor actor, String accountNumber) {
        AccountRow account = accounts.findByNumber(accountNumber)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!actor.isOperator() && !actor.isCustomerOf(account.customerId())) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return account;
    }

    private void auditOperatorRead(Actor actor, AccountRow account, String action, String correlationId) {
        if (actor.isOperator()) {
            audit.record(actor, action, "ACCOUNT", account.id(), "SUCCESS", correlationId, null);
        }
    }

    /** Balances are shown at storage precision (NUMBER(19,4)), e.g. "1250.0000". */
    private static String scale4(BigDecimal value) {
        return value.setScale(4, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }
}
