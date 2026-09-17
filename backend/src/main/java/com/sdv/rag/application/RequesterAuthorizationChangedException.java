package com.sdv.rag.application;

/**
 * Fixed, content-free signal that the persisted requester authorization no longer matches
 * the snapshot that admitted a discovery/answer operation.
 */
public final class RequesterAuthorizationChangedException extends RuntimeException {
    public RequesterAuthorizationChangedException() {
        super("requester authorization changed");
    }
}
