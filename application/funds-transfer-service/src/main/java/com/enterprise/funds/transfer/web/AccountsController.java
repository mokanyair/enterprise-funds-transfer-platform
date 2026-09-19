package com.enterprise.funds.transfer.web;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.TransferStatus;
import com.enterprise.funds.transfer.service.AccountService;
import com.enterprise.funds.transfer.web.dto.BalanceDto;
import com.enterprise.funds.transfer.web.dto.TransferPageDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Implements openapi/accounts/accounts-v1.yaml. */
@RestController
@RequestMapping(path = "/api/v1/accounts/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class AccountsController {

    private static final String ACCOUNT_ID = "^[A-Za-z0-9_-]{1,32}$";

    private final AccountService accounts;

    public AccountsController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/balance")
    public BalanceDto balance(Actor actor, @PathVariable @Pattern(regexp = ACCOUNT_ID) String accountId) {
        return accounts.balance(actor, accountId, CorrelationIdFilter.current());
    }

    @GetMapping("/transfers")
    public TransferPageDto history(
            Actor actor,
            @PathVariable @Pattern(regexp = ACCOUNT_ID) String accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) TransferStatus status) {
        return accounts.history(actor, accountId, page, size, status, CorrelationIdFilter.current());
    }
}
