package com.sdv.rag.api.dto;

import java.time.Instant;

public record RagCitation(Long documentId, String locatorType, String locatorValue, String sourceVersion,
        Instant verifiedAt, String downloadUrl) { }
