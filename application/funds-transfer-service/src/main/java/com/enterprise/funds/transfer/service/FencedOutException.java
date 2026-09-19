package com.enterprise.funds.transfer.service;

/**
 * Thrown inside the posting transaction when this attempt no longer owns its idempotency record because a
 * retry took it over. The transaction rolls back, so the superseded attempt can never post.
 */
public class FencedOutException extends RuntimeException {
    public FencedOutException() {
        super("Idempotency attempt was superseded", null, false, false);
    }
}
