package com.enterprise.funds.transfer.domain;

/** Closed set of API error codes. Mirrors ErrorCode in openapi/common/common-v1.yaml. */
public enum ErrorCode {
    INVALID_REQUEST(400, "The request is invalid."),
    UNAUTHENTICATED(401, "Authentication is required."),
    FORBIDDEN(403, "You are not permitted to perform this operation."),
    NOT_FOUND(404, "The requested resource was not found."),
    IDEMPOTENCY_KEY_REUSED(409, "This Idempotency-Key was already used with a different request."),
    IDEMPOTENCY_IN_PROGRESS(409, "A request with this Idempotency-Key is still being processed."),
    IDEMPOTENCY_KEY_EXPIRED(409,
            "This Idempotency-Key was already used and its stored response has expired. "
                    + "The original request was processed and has not been repeated."),
    TRANSFER_NOT_CANCELLABLE(409, "The transfer has already been posted and cannot be cancelled."),
    INSUFFICIENT_FUNDS(422, "The source account has insufficient available funds."),
    ACCOUNT_INACTIVE(422, "One of the accounts is not active."),
    CURRENCY_MISMATCH(422, "The currency does not match the accounts."),
    SAME_ACCOUNT(422, "Source and destination accounts must differ."),
    AMOUNT_NOT_POSITIVE(422, "The amount must be greater than zero."),
    LIMIT_EXCEEDED(422, "The transfer exceeds a configured limit."),
    TRANSFER_REJECTED(422, "The transfer was rejected."),
    INTERNAL_ERROR(500, "An unexpected error occurred. Quote the correlation ID when contacting support.");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() { return httpStatus; }

    public String defaultMessage() { return defaultMessage; }
}
