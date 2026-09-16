package com.sdv.rag.domain;

import java.util.Objects;

/** A deterministic, transient parse-time chunk coordinate; it never contains chunk text. */
public record ExtractedChunk(int chunkIndex, LocatorType locatorType, String locatorValue, int startOffset,
        int endOffset) {

    public ExtractedChunk {
        if (chunkIndex < 0 || startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("chunk coordinates must be non-negative and non-empty");
        }
        Objects.requireNonNull(locatorType, "locatorType must not be null");
        Objects.requireNonNull(locatorValue, "locatorValue must not be null");
    }
}
