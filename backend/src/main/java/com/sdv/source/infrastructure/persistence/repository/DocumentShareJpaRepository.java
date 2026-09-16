package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * M10B 신규 - {@code document_shares}(+ {@code document_share_recipients})
 * 조회/판단 경로. {@link SourceDocumentJpaRepository}가 이미 확립한 관례(매핑된
 * 연관관계 없이 JPQL {@code JOIN ... ON}만 사용, 선택적 필터는 {@code hasXxx}
 * Boolean + Non-null Sentinel 쌍으로 받아 실제 SQL {@code NULL}을 절대 바인딩하지
 * 않음 - PostgreSQL/Hibernate 7의 Named Parameter 재사용 Type 추론 결함을 구조적으로
 * 피한다)을 그대로 따른다.
 */
public interface DocumentShareJpaRepository extends JpaRepository<DocumentShareEntity, Long> {

    /** 소유자(게시자) 범위 단일 조회 - PATCH/DELETE/단건 GET이 쓴다. 다른 게시자 소유는 존재하지 않는 것과 동일하게 빈 결과다. */
    Optional<DocumentShareEntity> findByIdAndPublisherSubject(@Param("id") Long id,
            @Param("publisherSubject") String publisherSubject);

    /** {@code GET /api/shares} - 내가 게시한 모든 공유(철회된 이력 포함, 관리 목적으로 상태를 그대로 보여준다). */
    List<DocumentShareEntity> findAllByPublisherSubjectOrderByCreatedAtDesc(String publisherSubject);

    /** 문서당 활성 공유는 최대 하나(uq_document_shares_active_document) - 재공유/중복 게시 판단에 쓴다. */
    Optional<DocumentShareEntity> findByDocumentIdAndRevokedAtIsNull(Long documentId);

    /** {@code GET /api/admin/shares} - 활성 공유만 관리 대상이다(철회된 공유는 이미 아무 접근도 부여하지 않는다). */
    List<DocumentShareEntity> findAllByRevokedAtIsNullOrderByCreatedAtDesc();

    /** ADMIN 범위 단건 조회 - 게시자와 무관하게 활성 공유 전체에서 찾는다({@code SharedFileAdminController}). */
    Optional<DocumentShareEntity> findByIdAndRevokedAtIsNull(Long id);

    /**
     * M11 후속 교정 - {@code IndexOrchestrator.publishGeneration}(패키지가 달라
     * {@code @link}를 걸 수 없다, {@code com.sdv.rag.application.IndexOrchestrator})이
     * 발행 직전 이 공유 행을 {@code SELECT ... FOR UPDATE}로 잠그기 위한 조회다 -
     * {@code SourceDocumentJpaRepository.findByIdForUpdate}와 정확히 같은 기법이다.
     * "checking a mutable share without protecting against concurrent changes is
     * insufficient"(이 작업 지시사항) - 이 Lock이 없으면 발행 Transaction의
     * (읽기 전용) 공유 확인과 그 이후의 실제 Embedding 쓰기 사이에 다른 Transaction
     * ({@code updateShare}/{@code adminSetBlocked})이 끼어들어 이 공유를 바꿔도 발행
     * Transaction은 그 변경을 전혀 모른 채 진행할 수 있었다. Lock 순서는 항상 부모
     * {@code source_connections} → {@code source_documents} → 이 {@code
     * document_shares}다(IndexOrchestrator Class Javadoc 참고) - {@code
     * createShare}/{@code unshare}/{@code adminSetBlocked}는 이미 그 앞의
     * {@code source_documents} 행을 먼저 잠그므로 순서가 뒤집히지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM DocumentShareEntity s WHERE s.id = :shareId")
    Optional<DocumentShareEntity> findByIdForUpdate(@Param("shareId") Long shareId);

    // ------------------------------------------------------------------
    // document_share_recipients (자식 테이블) - 별도 Repository 없이 이 Repository
    // 안에서 JPQL로만 다룬다(이 M10B Slice가 그 이상의 독립 조회를 필요로 하지
    // 않는다 - 불필요한 추가 Repository Interface를 만들지 않는다).
    // ------------------------------------------------------------------

    /** 이 공유의 현재 수신자 subject 목록. */
    @Query("SELECT r.recipientSubject FROM DocumentShareRecipientEntity r WHERE r.shareId = :shareId")
    List<String> findRecipientSubjects(@Param("shareId") Long shareId);

    /** 소유자 갱신(PATCH) 시 기존 수신자 행을 전부 지우고 새 목록으로 다시 쓴다 - Bulk Delete만 이 메서드가 하고, 삽입은 호출자가 Entity로 저장한다. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM DocumentShareRecipientEntity r WHERE r.shareId = :shareId")
    void deleteRecipients(@Param("shareId") Long shareId);

    /** 이 요청자(Subject)가 지금 이 문서에 대해 갖는 활성 공유가 있는지 - 위조된 shareId로 접근을 넓힐 수 없음을 검증하는 Test 등에 쓴다. */
    @Query("SELECT (COUNT(s) > 0) FROM DocumentShareEntity s "
            + "JOIN DocumentShareRecipientEntity r ON r.shareId = s.id "
            + "WHERE s.documentId = :documentId AND r.recipientSubject = :recipientSubject "
            + "AND s.revokedAt IS NULL AND s.adminBlocked = false")
    boolean existsActiveGrantForRecipient(@Param("documentId") Long documentId,
            @Param("recipientSubject") String recipientSubject);

    /**
     * M10B 신규(공유 기반 File Metadata Discovery Prefilter) - Owner-Only이던
     * {@code SourceDocumentJpaRepository.searchDiscoverable}을 대체한다. 후보는
     * 더 이상 "내가 소유한 문서"가 아니라 "나(recipient)에게 명시적으로 활성 공유된
     * 문서"다 - Unshared 파일은(게시자 본인의 공통 검색을 포함해) 여기 절대
     * 나타나지 않는다(§2A.4 "including the owner's common discovery").
     *
     * <p>{@code FROM SourceDocumentEntity d}를 Root Alias로 유지한다 - {@code
     * FileMetadataDiscoveryService.buildSort()}가 넘기는 Bare Property 이름
     * ({@code name}/{@code modifiedAt}/{@code id})이 Spring Data의 자동 Alias
     * 추론으로 정확히 {@code d.*}에 적용되게 하기 위함이다({@code
     * searchDiscoverable}과 동일한 이유).</p>
     */
    @Query("SELECT new com.sdv.source.infrastructure.persistence.repository."
            + "DocumentShareJpaRepository$SharedDiscoveryCandidate(d, s, c.ownerSubject, c.connectionEpoch) "
            + "FROM SourceDocumentEntity d "
            + "JOIN DocumentShareEntity s ON s.documentId = d.id "
            + "JOIN DocumentShareRecipientEntity r ON r.shareId = s.id "
            + "JOIN SourceConnectionEntity c ON c.id = d.sourceId "
            + "WHERE r.recipientSubject = :recipientSubject AND s.revokedAt IS NULL AND s.adminBlocked = false "
            + "AND c.type = 'GOOGLE_DRIVE' AND c.status = 'ACTIVE' AND d.state = 'ACTIVE' "
            + "AND (:hasSourceId = false OR d.sourceId = :sourceId) "
            + "AND (:hasMimeType = false OR d.mimeType = :mimeType) "
            + "AND (:hasNamePattern = false OR LOWER(d.name) LIKE :namePattern ESCAPE '\\') "
            + "AND (:hasModifiedFrom = false OR d.modifiedAt >= :modifiedFrom) "
            + "AND (:hasModifiedTo = false OR d.modifiedAt <= :modifiedTo)")
    Slice<SharedDiscoveryCandidate> searchSharedDiscoverable(@Param("recipientSubject") String recipientSubject,
            @Param("hasSourceId") boolean hasSourceId, @Param("sourceId") Long sourceId,
            @Param("hasMimeType") boolean hasMimeType, @Param("mimeType") String mimeType,
            @Param("hasNamePattern") boolean hasNamePattern, @Param("namePattern") String namePattern,
            @Param("hasModifiedFrom") boolean hasModifiedFrom, @Param("modifiedFrom") Instant modifiedFrom,
            @Param("hasModifiedTo") boolean hasModifiedTo, @Param("modifiedTo") Instant modifiedTo,
            Pageable pageable);

    /**
     * {@link #searchSharedDiscoverable}의 한 행 - 후보 문서, 그 문서를 노출 대상으로
     * 만든 활성 공유, 그 공유의 게시자(Source Owner) Subject, 그리고 게시자 Source의
     * 현재 연결 인가 세대({@code connectionEpoch}, V011)를 함께 담는다. {@code
     * FileMetadataDiscoveryService}가 이 넷으로 {@link
     * com.sdv.source.domain.SourceAccessContext}를 조립하고, 게시자 Subject로
     * {@code UserContext}를 만들어 Live 재확인(Publisher-Bound Delegation)을 수행한다.
     * {@code connectionEpoch}는 노출 직전 재인가(같은 요청 안의 두 번째
     * {@code evaluateSharedAccess} 호출)가 Live I/O 도중의 Disconnect+재연결을
     * "아무 일도 없었던 것"으로 취급하지 않기 위한 값이다.
     */
    record SharedDiscoveryCandidate(SourceDocumentEntity document, DocumentShareEntity share,
            String publisherSubject, long connectionEpoch) {
    }
}
