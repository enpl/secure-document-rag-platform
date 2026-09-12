package com.sdv.rag.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;

/**
 * F-BE-106 (M07A 신규, v1.4 §2A.2/§2A.11). {@code document_embedding_index}
 * (V006)의 JPA Persistence 매핑 - 평문을 포함하지 않는 Embedding Candidate
 * Index 한 행(문서 하나의 Chunk 하나).
 *
 * <p><b>이 Entity가 절대 담지 않는 것:</b> 원본 Byte, 완전한 추출 텍스트,
 * 평문 Chunk, 근거(Evidence) 텍스트, 질문/답변/Prompt 본문 - v1.4 §2A.3.
 * {@link #contentHmac}는 원문을 복원할 수 없는 Keyed Digest/HMAC일 뿐이다
 * (동일 Content 재확인/재사용 판단용).</p>
 *
 * <h2>새 JPA/Hibernate 패턴 - pgvector {@code vector} Column 매핑</h2>
 * <p>{@code document_chunks}(V002)의 {@code embedding vector(1024)} 컬럼은
 * 지금까지 이 Backend의 어떤 Java Entity도 매핑한 적이 없었다(M06까지
 * pgvector Column을 Java에서 다룬 적이 없다) - 이 Entity가 이 Backend
 * 최초의 pgvector 매핑이다.</p>
 *
 * <p><b>실제로 검증하고 고른 방식(중요 - 처음 시도는 틀렸었다):</b>
 * Hibernate ORM 7.4({@code build.gradle}에 이미 존재, 새 Dependency 아님)는
 * {@code org.hibernate.type.SqlTypes.VECTOR_FLOAT32} 상수 자체는 갖고
 * 있지만, {@code hibernate-core}만으로는(이 프로젝트에 {@code pgvector-java}나
 * {@code hypersistence-utils} 같은 추가 Vector Type 라이브러리가 없다) 그
 * 상수에 대응하는 실제 JDBC Binding 구현이 등록되어 있지 않다 - 실제로
 * {@code @JdbcTypeCode(SqlTypes.VECTOR_FLOAT32)} + {@code float[]}로
 * 시도했더니 Testcontainers pgvector에 대해 "column is of type vector but
 * expression is of type bytea"로 실패하는 것을 직접 확인했다(추측이 아니라
 * 실행 결과). 새 Dependency를 추가하는 대신({@code CLAUDE.md}: "요청하지
 * 않은 Dependency를 추가하지 않는다"), Hibernate가 이미 갖고 있는
 * {@link ColumnTransformer}(별도 라이브러리 불필요)로 이 컬럼을 pgvector
 * 표준 텍스트 형식(예: {@code "[0.1,0.2,...]"})의 {@link String}으로
 * 왕복시킨다 - 쓸 때는 {@code CAST(? AS vector)}로, 읽을 때는
 * {@code embedding::text}로 명시적으로 변환한다. Java 쪽 표현이
 * {@code float[]}이 아니라 {@code String}이라는 점만 다를 뿐, DB Column
 * 자체는 여전히 진짜 {@code vector(1024)}이고 HNSW/Cosine 검색도 그대로
 * 동작한다(Repository의 {@code searchAllowed} 참고) - Manifest가 요구하는
 * "Embedding Vector" 개념/DB Column 형태를 그대로 만족한다.</p>
 */
@Entity
@Table(name = "document_embedding_index")
public class DocumentEmbeddingEntity {

    /** bge-m3:567m 차원 수(V002 Canonical 결정). */
    public static final int EMBEDDING_DIMENSIONS = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** {@code com.sdv.rag.domain.LocatorType}과 동일 어휘(PAGE/SLIDE/SHEET_RANGE/LINE_RANGE/SECTION/DOCUMENT). */
    @Column(name = "locator_type", nullable = false, length = 30)
    private String locatorType;

    @Column(name = "locator_value", nullable = false)
    private String locatorValue;

    /** pgvector 텍스트 형식(예: {@code "[0.1,0.2,...]"}) - Class Javadoc의 {@link ColumnTransformer} 설명 참고. */
    @ColumnTransformer(read = "embedding::text", write = "CAST(? AS vector)")
    @Column(name = "embedding", nullable = false, columnDefinition = "vector(1024)")
    private String embedding;

    /** 이 Generation을 만든 시점의 {@code source_documents.source_version} 스냅샷. */
    @Column(name = "source_version", nullable = false, length = 255)
    private String sourceVersion;

    /** 원본 Content의 Keyed Digest/HMAC Hex - 원문을 복원할 수 없다. */
    @Column(name = "content_hmac", nullable = false, length = 64)
    private String contentHmac;

    @Column(name = "parser_version", nullable = false, length = 50)
    private String parserVersion;

    /**
     * File Manifest(F-BE-106)의 공식 Java Contract 이름은 {@code modelVersion}이다
     * - DB Column 이름({@code embedding_model})과는 의도적으로 다르다({@code
     * CORE_SPEC.md}의 §"document_embedding_index" 목표 Schema는 DB Column을
     * {@code embedding_model}로, Manifest는 Java Property를 {@code modelVersion}으로
     * 각각 확정했다 - 둘 다 공개 명세이므로 이 Entity는 어느 쪽도 임의로
     * 바꾸지 않고 그대로 매핑만 잇는다). {@link #parserVersion}과는 별개의
     * 독립된 필드다 - 하나로 합치지 않는다.
     */
    @Column(name = "embedding_model", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    protected DocumentEmbeddingEntity() {
        // JPA
    }

    public DocumentEmbeddingEntity(Long documentId, Integer chunkIndex, String locatorType, String locatorValue,
            String embedding, String sourceVersion, String contentHmac, String parserVersion,
            String modelVersion, Instant indexedAt) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.locatorType = locatorType;
        this.locatorValue = locatorValue;
        this.embedding = embedding;
        this.sourceVersion = sourceVersion;
        this.contentHmac = contentHmac;
        this.parserVersion = parserVersion;
        this.modelVersion = modelVersion;
        this.indexedAt = indexedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public String getLocatorType() {
        return locatorType;
    }

    public String getLocatorValue() {
        return locatorValue;
    }

    public String getEmbedding() {
        return embedding;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public String getContentHmac() {
        return contentHmac;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }
}
