package com.enterprise.funds.transfer.web.dto;

import com.enterprise.funds.transfer.domain.CurrencyPolicy;
import com.enterprise.funds.transfer.persistence.Rows.TransferView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Response shape of a transfer. No internal ids, risk detail or user ids (contract). */
public record TransferDto(
        String transferId, String sourceAccountId, String destinationAccountId, String amount,
        String currency, String status, Instant createdAt, Instant updatedAt) {

    public static TransferDto from(TransferView v) {
        return new TransferDto(
                v.id().toString(), v.sourceNumber(), v.destinationNumber(),
                CurrencyPolicy.format(v.currency(), v.amount()), v.currency(), v.status().name(),
                v.createdAt().toInstant().truncatedTo(ChronoUnit.MILLIS),
                v.updatedAt().toInstant().truncatedTo(ChronoUnit.MILLIS));
    }
}
