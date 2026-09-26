package com.enterprise.funds.transfer.web.dto;

/** One row of GET /accounts. Same field shape as Balance so the UI can reuse formatting. */
public record AccountSummaryDto(
        String accountId, String currency, String status, String availableBalance, String ledgerBalance) {}
