package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
