package com.sdv.source.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * F-BE-020. 외부/Local Vault 문서 공통 표준 모델(SRC-004, SYN-002).
 *
 * {@code state}(Source 문서 생명주기)와 {@code indexStatus}/{@code indexReason}
 * (RAG/콘텐츠 색인 상태)을 명시적으로 구분한다 - 병합하지 않는다.
 * Source별 Google SDK 객체가 이 클래스로 새어 들어오지 않는다.
 */
public final class SourceDocument {

    private final Long id;
    private final Long sourceId;
    private final String sourceDocumentId;
    private final String name;
    private final String mimeType;
    private final String sourceVersion;
    private final Instant modifiedAt;
    private SourceDocumentState state;
    private DocumentIndexStatus indexStatus;
    private String indexReason;

    public SourceDocument(Long id, Long sourceId, String sourceDocumentId, String name, String mimeType,
            String sourceVersion, Instant modifiedAt, SourceDocumentState state, DocumentIndexStatus indexStatus,
            String indexReason) {
        this.id = id;
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
        this.sourceDocumentId = requireNonBlank(sourceDocumentId, "sourceDocumentId");
        this.name = requireNonBlank(name, "name");
        this.mimeType = mimeType;
        this.sourceVersion = sourceVersion;
        this.modifiedAt = modifiedAt;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.indexStatus = Objects.requireNonNull(indexStatus, "indexStatus must not be null");
        this.indexReason = indexReason;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** Source 연결 Disconnect 등으로 이 문서를 더 이상 유효한 후보로 취급하지 않는다. */
    public void markDeleted() {
        this.state = SourceDocumentState.DELETED;
    }

    public Long getId() {
        return id;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getSourceDocumentId() {
        return sourceDocumentId;
    }

    public String getName() {
        return name;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    public SourceDocumentState getState() {
        return state;
    }

    public DocumentIndexStatus getIndexStatus() {
        return indexStatus;
    }

    public String getIndexReason() {
        return indexReason;
    }
}
