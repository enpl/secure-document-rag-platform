package com.sdv.rag.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * F-BE (M06 신규). {@code document_extracted_content}(V005)의 JPA Persistence
 * 매핑 - 문서당 "현재 사용 가능한 추출 결과" 하나(POL-계열 Retrieval Handoff
 * 직전 단계, M11이 소비할 입력).
 *
 * <p><b>이 Entity를 통한 일반 {@code save()}로 Claim/발행(Publish)을 하지
 * 않는다.</b> 실제 배타적 처리 권한 획득(Claim)과 원자적 조건부 발행은
 * {@link com.sdv.rag.infrastructure.persistence.repository.DocumentExtractedContentJpaRepository}의
 * 원자적 조건부 SQL({@code INSERT ... ON CONFLICT ... WHERE},
 * {@code UPDATE ... WHERE})로만 수행한다 - "읽고 - 메모리에서 바꾸고 -
 * 저장"(Check-Then-Save) 경로는 동시 요청 사이의 Race를 막지 못한다. 이
 * Entity는 결과를 읽거나(M11 소비, 테스트 검증), 테스트 Fixture를 직접
 * 구성할 때만 사용한다.</p>
 *
 * <p>{@link #isPublished()}가 {@code false}면(즉 {@code publishedAt == null})
 * 이 행이 존재하더라도 사용 가능한 결과가 아니다 - Claim만 진행 중이거나
 * 방치된 Claim일 수 있다(행 존재 자체는 증거가 아니다).</p>
 */
@Entity
@Table(name = "document_extracted_content")
public class DocumentExtractedContentEntity {

    @Id
    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "attempt_id")
    private UUID attemptId;

    @Column(name = "attempt_started_at")
    private Instant attemptStartedAt;

    @Column(name = "source_version")
    private String sourceVersion;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "parser_name")
    private String parserName;

    @Column(name = "parser_version")
    private String parserVersion;

    @Column(name = "normalization_version")
    private String normalizationVersion;

    @Column(name = "normalized_text")
    private String normalizedText;

    /** 원시 JSON 배열 텍스트({@link com.sdv.rag.domain.ExtractedLocation} 목록) - Postgres 쪽 질의가 필요 없어 TEXT로 저장한다(V005 참고). */
    @Column(name = "locations")
    private String locationsJson;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected DocumentExtractedContentEntity() {
        // JPA
    }

    /** 테스트 Fixture 등에서 발행된 상태를 직접 구성할 때 사용한다. */
    public DocumentExtractedContentEntity(Long documentId, UUID attemptId, Instant attemptStartedAt,
            String sourceVersion, String contentHash, String parserName, String parserVersion,
            String normalizationVersion, String normalizedText, String locationsJson, Instant publishedAt) {
        this.documentId = documentId;
        this.attemptId = attemptId;
        this.attemptStartedAt = attemptStartedAt;
        this.sourceVersion = sourceVersion;
        this.contentHash = contentHash;
        this.parserName = parserName;
        this.parserVersion = parserVersion;
        this.normalizationVersion = normalizationVersion;
        this.normalizedText = normalizedText;
        this.locationsJson = locationsJson;
        this.publishedAt = publishedAt;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public Instant getAttemptStartedAt() {
        return attemptStartedAt;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getParserName() {
        return parserName;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public String getNormalizationVersion() {
        return normalizationVersion;
    }

    public String getNormalizedText() {
        return normalizedText;
    }

    public String getLocationsJson() {
        return locationsJson;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    /** {@code false}면 행이 존재해도 아직(또는 더 이상) 사용 가능한 결과가 아니다. */
    public boolean isPublished() {
        return publishedAt != null;
    }
}
