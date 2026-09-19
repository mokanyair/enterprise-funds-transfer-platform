package com.enterprise.funds.transfer.domain;

import java.util.UUID;

/**
 * The authenticated caller, resolved from the verified token subject through APP_USERS. Never built from
 * anything the client sends. customerId is null for operators.
 */
public record Actor(UUID userId, Role role, UUID customerId) {

    public boolean isOperator() { return role == Role.OPERATOR; }

    public boolean isCustomerOf(UUID customer) {
        return role == Role.CUSTOMER && customerId != null && customerId.equals(customer);
    }
}
