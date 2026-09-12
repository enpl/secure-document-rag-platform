package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * F-BE-036. source_documents Persistence/조회(SRC-004, RAG-004).
 *
 * <p>{@code findBySourceAndSourceDocId}/{@code findIndexEligibleIds}는
 * Manifest가 지정한 canonical 메서드명을 그대로 사용한다.
 * {@code findBySourceIdAndStateNot}/{@code markAllActiveAsDeletedForSource}는
 * M04의 Disconnect 요구사항(비삭제 문서를 DELETED로 전이, 기본 문서 조회는
 * DELETED를 제외)을 위해 그 작업에서 추가한 최소 조회/변경 메서드다.
 * {@code findByIdAndOwnerSubject}는 M05가 {@code source_permissions →
 * source_documents → source_connections.owner_subject} 경계를 연결하기 위해
 * 추가한 단 하나의 최소 Owner-Scoped 조회다(새 Migration 없이 기존 V001/V004
 * 컬럼만으로 JOIN한다) - {@code EffectivePermissionService}가 문서를 로드할 때
 * 다른 계정 소유 문서를 절대 반환하지 않는다.</p>
 *
 * <p>{@code updateIndexStatus}/{@code deleteExtractedContentForSource}는 M06이
 * 추가한 최소 메서드다. {@code deleteExtractedContentForSource}는 이 Source(RAG
 * 소유가 아닌 Source 소유) Repository 안에서 {@code document_extracted_content}
 * (V005, RAG 소유 테이블)를 테이블 이름만으로 참조하는 Native Query다 -
 * Source 도메인 Java 코드가 {@code com.sdv.rag.*} 클래스를 Import하지 않는다는
 * 의존 방향 규칙(RAG는 Source에 의존할 수 있지만 반대는 안 됨)을 SQL 문자열
 * 수준에서만, 이 메서드 하나로 좁게 우회한다 - 기존 {@code markAllActiveAsDeletedForSource}
 * (논리적 삭제, Bulk UPDATE)가 FK {@code ON DELETE CASCADE}를 발동시키지 않으므로,
 * Disconnect 시 추출된 텍스트를 정리할 다른 방법이 없기 때문이다
 * ({@code SourceConnectionService.disconnect}가 두 메서드를 같은 Transaction
 * 안에서 함께 호출한다).</p>
 */
public interface SourceDocumentJpaRepository extends JpaRepository<SourceDocumentEntity, Long> {

    @Query("SELECT e FROM SourceDocumentEntity e "
            + "WHERE e.sourceId = :sourceId AND e.sourceDocumentId = :sourceDocumentId")
    Optional<SourceDocumentEntity> findBySourceAndSourceDocId(Long sourceId, String sourceDocumentId);

    /**
     * Policy 평가가 필요로 하는 유일한 Owner-Scoped 문서 조회. {@code source_documents}를
     * {@code source_connections}와 ID로만 JOIN한다(매핑된 연관관계 없이 명시적 ON) - 다른
     * 계정 소유 문서, 또는 존재하지 않는 문서는 동일하게 빈 결과로 반환된다(M04
     * {@code NotFoundException} 패턴과 동일하게 존재 여부를 구분해 노출하지 않는다).
     */
    @Query("SELECT d FROM SourceDocumentEntity d JOIN SourceConnectionEntity c ON c.id = d.sourceId "
            + "WHERE d.id = :documentId AND c.ownerSubject = :ownerSubject")
    Optional<SourceDocumentEntity> findByIdAndOwnerSubject(@Param("documentId") Long documentId,
            @Param("ownerSubject") String ownerSubject);

    /** 기본(Default) 문서 조회 - DELETED를 제외한다(INV-SRC-003). */
    List<SourceDocumentEntity> findBySourceIdAndStateNot(Long sourceId, String excludedState);

    /**
     * Source Disconnect 시 비삭제 문서를 일괄 DELETED로 전이한다. 이미 DELETED인
     * 행은 건드리지 않으므로(WHERE state <> 'DELETED') 반복 호출해도 멱등적이다.
     */
    @Modifying
    @Query("UPDATE SourceDocumentEntity e SET e.state = 'DELETED' "
            + "WHERE e.sourceId = :sourceId AND e.state <> 'DELETED'")
    int markAllActiveAsDeletedForSource(Long sourceId);

    /** RAG 검색 후보 ID - state=ACTIVE AND indexStatus=INDEXED(M01 Indexed Mode 정의). */
    @Query("SELECT e.id FROM SourceDocumentEntity e WHERE e.state = 'ACTIVE' AND e.indexStatus = 'INDEXED'")
    List<Long> findIndexEligibleIds();

    /**
     * M06 - {@code index_status}/{@code index_reason}을 기록한다. 실패/미지원/
     * 텍스트 없음 결과뿐 아니라, 성공한 (재)추출이 이전 {@code FAILED}/
     * {@code INDEXED} 등 낡은 값을 남겨두지 않도록 {@code PENDING}(및 Reason
     * {@code null})으로 재설정할 때도 이 메서드를 그대로 재사용한다(M06 후속
     * 교정 - 이전에는 성공 시 이 컬럼을 전혀 건드리지 않아, FAILED였던
     * 문서가 성공적으로 재추출돼도 index_status가 FAILED로 남는 결함이
     * 있었다). {@code ContentProcessingPolicy}가 호출자 쪽에서 어떤 값을
     * 넘길지 결정한다 - 새 {@code DocumentIndexStatus} 값을 추가하지 않는다.
     */
    @Modifying
    @Query("UPDATE SourceDocumentEntity e SET e.indexStatus = :indexStatus, e.indexReason = :indexReason "
            + "WHERE e.id = :documentId")
    void updateIndexStatus(@Param("documentId") Long documentId, @Param("indexStatus") String indexStatus,
            @Param("indexReason") String indexReason);

    /**
     * M06 후속 교정. {@code ContentExtractionService.finalizePublish}가 발행
     * 직전 재검증(ACTIVE 상태 등)에 쓰는 행 단위 비관적 쓰기 Lock(
     * {@code SELECT ... FOR UPDATE}) 조회다. 이 Lock은 같은 행을 바꾸려는
     * 동시 {@code SourceConnectionService.disconnect}(Bulk UPDATE)의 해당
     * 행 변경을 이 Transaction이 끝날 때까지 대기시켜, "발행 직전 읽은 상태"와
     * "실제 발행이 반영되는 시점" 사이에 Disconnect가 끼어들 수 있는 경쟁을
     * 없앤다(단순 SELECT 뒤에 별도 UPDATE를 하는 것만으로는 그 사이의 끼어듦을
     * 막지 못한다). Lock 대상은 {@code source_documents} 행 하나뿐이다 -
     * Disconnect가 먼저 {@code source_connections}를, 그 다음 {@code source_documents}를
     * 바꾸는 순서와 겹치는 두 번째 단계에서만 만나므로 Lock 순서 역전으로 인한
     * Deadlock 위험이 없다({@code source_connections}는 여기서 Lock을 걸지
     * 않는다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM SourceDocumentEntity e WHERE e.id = :documentId")
    Optional<SourceDocumentEntity> findByIdForUpdate(@Param("documentId") Long documentId);

    /**
     * M06 - Source Disconnect(논리적 삭제) 시 이 Source에 속한 모든 문서의
     * 추출된 텍스트를 제거한다. {@code markAllActiveAsDeletedForSource}(Bulk
     * UPDATE)는 FK {@code ON DELETE CASCADE}를 발동시키지 않으므로 별도로
     * 호출해야 한다 - 클래스 Javadoc의 의존 방향 설명 참고.
     */
    @Modifying
    @Query(value = "DELETE FROM document_extracted_content "
            + "WHERE document_id IN (SELECT id FROM source_documents WHERE source_id = :sourceId)",
            nativeQuery = true)
    int deleteExtractedContentForSource(@Param("sourceId") Long sourceId);
}
