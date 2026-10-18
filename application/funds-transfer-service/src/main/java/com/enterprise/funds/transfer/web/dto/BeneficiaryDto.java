package com.enterprise.funds.transfer.web.dto;

import com.enterprise.funds.transfer.persistence.Rows.BeneficiaryRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Response shape of a beneficiary. Deliberately no holder name, status or currency of the destination account:
 * those belong to another customer (decision B4).
 */
public record BeneficiaryDto(String beneficiaryId, String accountId, String nickname, Instant createdAt) {

    public static BeneficiaryDto from(BeneficiaryRow r) {
        return new BeneficiaryDto(r.id().toString(), r.accountNumber(), r.nickname(),
                r.createdAt().toInstant().truncatedTo(ChronoUnit.MILLIS));
    }
}
