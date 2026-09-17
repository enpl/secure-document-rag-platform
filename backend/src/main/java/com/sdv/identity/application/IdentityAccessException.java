package com.sdv.identity.application;

public class IdentityAccessException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, NOT_FOUND, CONFLICT, AMBIGUOUS }
    private final Reason reason;
    public IdentityAccessException(Reason reason, String message) { super(message); this.reason = reason; }
    public Reason reason() { return reason; }
}
