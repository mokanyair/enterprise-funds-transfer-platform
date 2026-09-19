package com.enterprise.funds.transfer.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A failure that maps to a documented API error. Messages are safe to show to callers and never carry
 * internal detail (contract rule). Extra response headers (Retry-After, Location, Idempotent-Replayed)
 * travel with the exception so the web layer stays free of business rules.
 */
public class ApiException extends RuntimeException {

    public record Detail(String field, String issue) {}

    private final ErrorCode code;
    private final List<Detail> details = new ArrayList<>();
    private final Map<String, String> headers = new LinkedHashMap<>();

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage());
    }

    public ApiException(ErrorCode code, String message) {
        super(message, null, false, false); // no stack trace: these are expected control flow
        this.code = code;
    }

    public ApiException detail(String field, String issue) {
        details.add(new Detail(field, issue));
        return this;
    }

    public ApiException header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public ErrorCode code() { return code; }

    public int status() { return code.httpStatus(); }

    public List<Detail> details() { return List.copyOf(details); }

    public Map<String, String> headers() { return Map.copyOf(headers); }

    /** Only 422 outcomes are stored against the idempotency key and replayed (contract, createTransfer). */
    public boolean isStorableOutcome() { return code.httpStatus() == 422; }
}
