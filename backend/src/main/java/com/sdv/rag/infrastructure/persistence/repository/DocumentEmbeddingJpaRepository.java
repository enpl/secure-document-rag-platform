package com.sdv.rag.infrastructure.persistence.repository;

import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * F-BE-107 (M07A 신규, v1.4 §2A.2/§2A.11). {@code document_embedding_index}
 * (V006) Persistence - 평문 없는 Embedding Candidate Index의 원자적 Generation
 * 교체/삭제/후보 검색.
 *
 * <p><b>{@link #replaceGeneration}이 원자적인 이유:</b> 문서 하나는 항상
 * "현재 Generation" 하나만 유효해야 한다(오래된 Chunk가 새 Chunk와 섞여
 * 남아있으면 안 된다). 삭제와 삽입을 별도 호출로 나누면 그 사이에 障害가
 * 나거나 동시 호출이 끼어들 때 부분 상태(일부만 지워진/일부만 채워진
 * Generation)가 남을 수 있다 - {@code @Transactional}로 두 SQL 문을 하나의
 * DB Transaction에 묶어 전부 성공하거나 전부 Rollback되게 한다.</p>
 *
 * <p><b>M07A 후속 교정 - 동시성 Review에서 발견된 실제 결함(고쳐짐):</b>
 * {@code @Transactional} + Delete-then-Insert만으로는 "이 문서에 아직
 * Embedding 행이 하나도 없을 때" 두 개의 동시 {@code replaceGeneration}
 * 호출을 직렬화하지 못한다 - 둘 다 {@code deleteByDocumentId}가 0행에
 * 적용되고(잠글 기존 행 자체가 없다), 서로 다른 두 Generation을 각각
 * {@code INSERT}해 둘 다 살아남을 수 있었다(실제로 재현 확인함, 아래
 * {@code DocumentEmbeddingJpaRepositoryTest}의 동시성 Test 참고). 이제는
 * {@link #lockSourceDocumentForGenerationReplacement}로 이 문서에 대응하는
 * {@code source_documents} 행 자체를 {@code SELECT ... FOR UPDATE}로 먼저
 * 잠근다 - Embedding 행이 있든 없든 항상 잠글 대상(문서 자신)이 존재하므로,
 * 두 번째 호출은 첫 번째 호출이 Commit할 때까지 진짜로 대기한다(JVM 안의
 * Lock이 아니라 Postgres 자체의 행 Lock - 여러 Backend Instance에서도
 * 동일하게 안전하다).</p>
 *
 * <p><b>이 Native Query가 {@code source_documents}를 테이블 이름만으로
 * 참조하는 이유:</b> {@code SourceDocumentJpaRepository.deleteEmbeddingIndexForSource}가
 * 반대 방향(Source → RAG)으로 이미 쓰는 것과 정확히 같은 기법이다 - Java
 * Import 없이 SQL 문자열 수준에서만 상대 Domain의 테이블을 참조해, "RAG는
 * Source에 의존할 수 있지만 반대는 안 된다"는 의존 방향 규칙을 어느 쪽도
 * 깨지 않는다.</p>
 *
 * <p><b>왜 이 Repository가 직접 {@code @Transactional}을 갖는가(예외적):</b>
 * CLAUDE.md/AGENTS.md의 "Application Service가 Use Case와 Transaction
 * Boundary를 담당한다" 원칙과 형식적으로 다르게 보일 수 있다 - 그러나 이
 * Generation 교체를 실제로 호출할 Application Service(M11 RAG Ingestion
 * Orchestration)는 이 작업(M07A) 범위 밖이라 아직 존재하지 않는다("Do not
 * fake later M11 orchestration or invent adjacent services" - 이번 작업
 * 지시사항). 존재하지 않는 Service를 지금 지어내는 대신, 이 Persistence
 * 기초 자체가 최소한의 원자성을 스스로 보장하도록 했다 - M11이 실제
 * Orchestration Service를 만들 때 이 메서드를 그대로(또는 자신의 더 넓은
 * Transaction 안에서) 호출할 수 있다.</p>
 *
 * <p><b>{@link #searchAllowed}의 권한 경계:</b> 이 메서드는 그 자체로 권한
 * 판단을 하지 않는다 - {@code allowedDocumentIds}는 호출자가
 * {@code EffectivePermissionService.filterAllowed(...)}(이미 M05가 확립한
 * 유일한 "허용 문서 ID" 산출 경로)로 미리 계산해 넘겨야 하는 값이다.
 * 비어있는 {@code allowedDocumentIds}는 "제한 없음"이 아니라 "허용된 문서가
 * 하나도 없음"으로 취급되어야 한다(INV-RAG-002) - 이 계약을 지키는 것은
 * 호출자의 책임이다(이 메서드는 받은 ID 목록 밖의 문서를 검색 결과에
 * 포함하지 않을 뿐이다). {@code queryEmbedding}은 pgvector 텍스트 입력
 * 형식(예: {@code "[0.1,0.2,...]"})이어야 한다 - 이 Native Query 하나의
 * Parameter 대상으로만 그 형식을 직접 {@code CAST}하므로, 별도 pgvector
 * Java Client 없이도 안전하게 동작한다. (M07A 후속 교정: 이 Javadoc은 한때
 * Entity의 {@code embedding} 필드가 {@code VECTOR_FLOAT32}
 * {@code JdbcTypeCode}로 매핑된다고 잘못 서술했었다 - 실제로 채택/검증된
 * 방식은 {@link com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity}의
 * Class Javadoc이 설명하는 {@code @ColumnTransformer} 기반 pgvector 텍스트
 * 왕복이다. Entity 필드 매핑과 이 Native Query의 Parameter Cast는 서로
 * 다른, 독립적인 두 경로다 - 이 문단이 그 사실을 정확히 반영하도록
 * 고쳤다.)</p>
 */
public interface DocumentEmbeddingJpaRepository extends JpaRepository<DocumentEmbeddingEntity, Long> {

    /**
     * 이 문서에 속한 모든 Embedding 행을 제거한다(이전 Generation 전부,
     * Generation 값과 무관하게) - {@link #replaceGeneration}이 내부적으로
     * 쓰거나, Source Disconnect/문서 삭제 시 단독으로도 쓸 수 있다.
     */
    @Modifying
    @Query("DELETE FROM DocumentEmbeddingEntity e WHERE e.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * {@link #replaceGeneration}이 동시 호출끼리 직렬화되도록, 이 문서에
     * 대응하는 {@code source_documents} 행 하나를 {@code SELECT ... FOR
     * UPDATE}로 잠근다 - Class Javadoc의 "동시성 Review에서 발견된 실제
     * 결함" 설명 참고. 반환값(잠긴 행의 {@code id})은 쓰지 않는다 - Lock
     * 확보 자체가 목적이다. {@code documentId}에 대응하는 {@code
     * source_documents} 행이 없으면(잘못된 호출) {@link Optional#empty()} -
     * 이 경우 잠글 대상 자체가 없으므로 조용히 넘어간다(뒤이은 삽입이 FK
     * 제약 위반으로 올바르게 실패한다).
     */
    @Query(value = "SELECT id FROM source_documents WHERE id = :documentId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockSourceDocumentForGenerationReplacement(@Param("documentId") Long documentId);

    /**
     * 문서 하나의 "현재 Generation"을 원자적으로 교체한다 - 기존 Generation을
     * (있다면) 전부 지우고 {@code newGeneration}으로 완전히 대체한다.
     * {@code newGeneration}이 비어있으면 삭제만 일어난다(Generation을
     * 0개로 만드는 것도 유효한 결과다 - 예: 재추출 결과가 텍스트 없음으로
     * 끝난 경우). 저장된 행을 반환한다.
     *
     * <p>삭제 전에 입력을 먼저 검증한다({@link #validateGeneration}) -
     * 유효하지 않은 배치는 기존 Generation을 전혀 건드리지 않고 즉시
     * {@link IllegalArgumentException}으로 실패한다("삭제 후에 실패"를
     * 절대 만들지 않는다).</p>
     */
    @Transactional
    default List<DocumentEmbeddingEntity> replaceGeneration(Long documentId,
            List<DocumentEmbeddingEntity> newGeneration) {
        validateGeneration(documentId, newGeneration);
        // 동시 호출 직렬화 - Class Javadoc 참고. 반환값은 쓰지 않는다.
        lockSourceDocumentForGenerationReplacement(documentId);
        deleteByDocumentId(documentId);
        return saveAll(newGeneration);
    }

    /**
     * {@link #replaceGeneration}의 삭제 전 입력 검증. 다음을 모두 만족해야
     * 한다 - 하나라도 위반하면 무엇도 지우지 않고 즉시 실패한다:
     * <ul>
     *   <li>{@code documentId}는 {@code null}이 아니다.</li>
     *   <li>{@code newGeneration}은 {@code null}이 아니고, {@code null} 행을
     *       담지 않는다.</li>
     *   <li>모든 행이 {@code documentId}에 속한다(다른 문서의 행이 섞이지
     *       않는다).</li>
     *   <li>비어있지 않은 배치는 정확히 하나의 Generation을 나타낸다 - 모든
     *       행의 {@code sourceVersion}/{@code parserVersion}/
     *       {@code modelVersion}이 같다.</li>
     *   <li>{@code chunkIndex}는 {@code null}이 아니고 음수가 아니며, 배치
     *       안에서 중복되지 않는다.</li>
     * </ul>
     */
    private static void validateGeneration(Long documentId, List<DocumentEmbeddingEntity> newGeneration) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(newGeneration, "newGeneration must not be null");
        if (newGeneration.isEmpty()) {
            return;
        }
        String sourceVersion = null;
        String parserVersion = null;
        String modelVersion = null;
        Set<Integer> seenChunkIndexes = new HashSet<>();
        for (DocumentEmbeddingEntity row : newGeneration) {
            if (row == null) {
                throw new IllegalArgumentException("newGeneration must not contain a null row");
            }
            if (!documentId.equals(row.getDocumentId())) {
                throw new IllegalArgumentException("every row in newGeneration must belong to documentId "
                        + documentId + ", found a row for documentId " + row.getDocumentId());
            }
            if (sourceVersion == null) {
                sourceVersion = row.getSourceVersion();
                parserVersion = row.getParserVersion();
                modelVersion = row.getModelVersion();
            } else if (!sourceVersion.equals(row.getSourceVersion()) || !parserVersion.equals(row.getParserVersion())
                    || !modelVersion.equals(row.getModelVersion())) {
                throw new IllegalArgumentException("every row in newGeneration must share the same "
                        + "sourceVersion/parserVersion/modelVersion (exactly one generation) - "
                        + "found more than one combination");
            }
            Integer chunkIndex = row.getChunkIndex();
            if (chunkIndex == null || chunkIndex < 0) {
                throw new IllegalArgumentException("chunkIndex must be a non-negative integer");
            }
            if (!seenChunkIndexes.add(chunkIndex)) {
                throw new IllegalArgumentException("duplicate chunkIndex " + chunkIndex
                        + " within the submitted batch");
            }
        }
    }

    /**
     * 이미 허용된 것으로 계산된 문서 ID 범위 안에서만 Cosine Distance 기준
     * 가장 가까운 후보를 반환한다("Candidate"일 뿐이다 - 권한 증명도 답변
     * 근거도 아니다, v1.4 §2A.4).
     */
    @Query(value = "SELECT * FROM document_embedding_index "
            + "WHERE document_id IN (:allowedDocumentIds) "
            + "ORDER BY embedding <=> CAST(:queryEmbedding AS vector) "
            + "LIMIT :limit",
            nativeQuery = true)
    List<DocumentEmbeddingEntity> searchAllowed(@Param("allowedDocumentIds") Collection<Long> allowedDocumentIds,
            @Param("queryEmbedding") String queryEmbedding, @Param("limit") int limit);
}
