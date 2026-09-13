package com.sdv.source.domain;

import java.util.Objects;

/**
 * M08 신규 - {@link SourceChangePage} 안의 변경 항목 하나. {@code type ==
 * REMOVED_OR_ACCESS_LOST}이면 {@link #document()}는 항상 {@code null}이다
 * (Google 자체가 그 경우 {@code file}을 채우지 않는다 - 지어내지 않는다).
 */
public record SourceChangeRecord(String sourceDocumentId, SourceChangeType type, SourceDocument document) {

    public SourceChangeRecord {
        Objects.requireNonNull(sourceDocumentId, "sourceDocumentId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (type == SourceChangeType.REMOVED_OR_ACCESS_LOST && document != null) {
            throw new IllegalArgumentException("document must be null for REMOVED_OR_ACCESS_LOST");
        }
        if (type == SourceChangeType.CHANGED) {
            Objects.requireNonNull(document, "document must not be null for CHANGED");
        }
    }
}
