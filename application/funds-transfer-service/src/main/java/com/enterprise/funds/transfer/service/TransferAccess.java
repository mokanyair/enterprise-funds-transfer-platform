package com.enterprise.funds.transfer.service;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.persistence.Rows.TransferView;

/** Authorization matrix for transfers (docs/milestone-1-contract.md section 2). */
final class TransferAccess {

    private TransferAccess() {}

    /** Owner of either account, or an operator. Everyone else must see an identical 404. */
    static boolean canView(Actor actor, TransferView v) {
        return actor.isOperator()
                || actor.isCustomerOf(v.sourceCustomerId())
                || actor.isCustomerOf(v.destinationCustomerId());
    }

    /** Only the source-account owner or an operator. A destination-only owner can see but not cancel (403). */
    static boolean canCancel(Actor actor, TransferView v) {
        return actor.isOperator() || actor.isCustomerOf(v.sourceCustomerId());
    }
}
