package com.enterprise.funds.transfer.notifications;

/** An event that can never be processed (bad JSON, missing envelope fields). Not retried: it goes to the DLT. */
public class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
