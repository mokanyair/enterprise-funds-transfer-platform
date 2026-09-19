package com.enterprise.funds.transfer.web.dto;

import java.time.Instant;

public record BalanceDto(String accountId, String currency, String availableBalance, String ledgerBalance, Instant asOf) {}
