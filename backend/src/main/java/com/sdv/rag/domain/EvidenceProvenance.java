package com.sdv.rag.domain;

import java.time.Instant;

/** Server-side provenance bound to the same handle and fresh authorization used for plaintext release. */
public record EvidenceProvenance(Long documentId, Long sourceId, String publisherSubject, Long shareId, long shareGeneration,
        long connectionGeneration, long requesterAuthorizationRevision, String sourceVersion,
        LocatorType locatorType, String locatorValue, Instant createdAt, Instant expiresAt, Instant verifiedAt) {
    public EvidenceProvenance(Long documentId, Long sourceId, String publisherSubject, Long shareId,
            long shareGeneration, long connectionGeneration, String sourceVersion, LocatorType locatorType,
            String locatorValue, Instant createdAt, Instant expiresAt, Instant verifiedAt) {
        this(documentId, sourceId, publisherSubject, shareId, shareGeneration, connectionGeneration, -1L,
                sourceVersion, locatorType, locatorValue, createdAt, expiresAt, verifiedAt);
    }
}
