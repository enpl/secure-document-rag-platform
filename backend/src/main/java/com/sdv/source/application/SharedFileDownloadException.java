package com.sdv.source.application;

/** Content-free failure used by the shared download HTTP boundary. */
public class SharedFileDownloadException extends RuntimeException {

    public enum Reason {
        NOT_AUTHORIZED,
        NOT_AVAILABLE,
        DOCUMENT_CHANGED,
        EXPORT_LIMIT_EXCEEDED,
        FILE_TOO_LARGE,
        REQUEST_TIMEOUT,
        CAPACITY_EXHAUSTED
    }

    private final Reason reason;

    public SharedFileDownloadException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
