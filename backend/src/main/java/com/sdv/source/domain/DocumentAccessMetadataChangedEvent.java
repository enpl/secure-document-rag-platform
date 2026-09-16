package com.sdv.source.domain;

/**
 * Domain-neutral signal that committed provider-ACL or explicit-publication metadata for one
 * catalog document changed. Consumers must re-read current state; the event is not authorization
 * evidence and deliberately carries no filename, ACL value, classification, or content.
 */
public record DocumentAccessMetadataChangedEvent(Long documentId) {
    public DocumentAccessMetadataChangedEvent {
        if (documentId == null || documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
    }
}
