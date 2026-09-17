package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
 * <p>{@code updateIndexStatus}/{@code deleteEmbeddingIndexForSource}는 M06/M07A가
 * 추가한 최소 메서드다. {@code deleteEmbeddingIndexForSource}는 이 Source(RAG
 * 소유가 아닌 Source 소유) Repository 안에서 {@code document_embedding_index}
 * (V006, RAG 소유 테이블)를 테이블 이름만으로 참조하는 Native Query다 -
 * Source 도메인 Java 코드가 {@code com.sdv.rag.*} 클래스를 Import하지 않는다는
 * 의존 방향 규칙(RAG는 Source에 의존할 수 있지만 반대는 안 됨)을 SQL 문자열
 * 수준에서만, 이 메서드 하나로 좁게 우회한다 - 기존 {@code markAllActiveAsDeletedForSource}
 * (논리적 삭제, Bulk UPDATE)가 FK {@code ON DELETE CASCADE}를 발동시키지 않으므로,
 * Disconnect 시 Embedding Index 행을 정리할 다른 방법이 없기 때문이다
 * ({@code SourceConnectionService.disconnect}가 두 메서드를 같은 Transaction
 * 안에서 함께 호출한다).</p>
 *
 * <p><b>M07A 교정:</b> 이 메서드는 원래 {@code document_extracted_content}
 * (V005)를 대상으로 하는 {@code deleteExtractedContentForSource}였다. V006이
 * 그 테이블 자체를 (구조와 데이터 모두) 제거했으므로, 같은 역할을
 * {@code document_embedding_index}(V006)를 대상으로 하도록 이름과 SQL을
 * 함께 교정했다 - Disconnect가 더 이상 존재하지 않는 테이블을 참조하면
 * Application이 Runtime에 그 지점에서 실패한다.</p>
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
     * M11 후속 교정 - {@link IndexOrchestrator}(패키지가 달라 Javadoc {@code @link}를
     * 걸 수 없어 이름으로만 언급한다, {@code com.sdv.rag.application.IndexOrchestrator})의
     * SKIPPED_UNSUPPORTED/SKIPPED_NO_TEXT 같은 비-INDEXED 종결 기록 전용 조건부
     * 버전이다. 이 문서의 현재 {@code source_version}이 호출자가 캡처해 둔
     * {@code expectedSourceVersion}과 여전히 같고, 아직 {@code INDEXED}가 아닐 때만
     * 실제로 갱신한다 - 이미 더 새로운 Generation이 발행됐거나(다른 Version) 이미
     * 성공적으로 색인된 뒤라면(비록 더 오래된 시도의 실패/미지원 판정이 뒤늦게
     * 도착해도) 그 최신 상태를 덮어쓰지 않는다("Old work must never overwrite/downgrade
     * a newer generation's terminal state"). 반환값(갱신된 행 수)은 실제로 적용됐는지
     * 여부를 호출자가 알 수 있게 한다 - 0이면 조용히 무시된(Fenced-out) 것이다.
     *
     * <p><b>M11 후속 교정(이번 작업 지시사항 A)</b>: 호출자({@code
     * IndexOrchestrator.finalizeNonSuccess})는 이제 이 SQL {@code WHERE} 절만으로
     * 펜싱을 끝내지 않는다 - 호출 전에 이미 같은 짧은 새 Transaction 안에서 연결
     * {@code connectionEpoch}/공유 {@code shareId}+{@code generation}까지 잠그고
     * 재확인한다("indexStatus != INDEXED" alone is not a generation fence). 이
     * Method의 {@code source_version}+비-INDEXED 조건은 그 Java 레벨 확인 위에 얹는
     * SQL 레벨 이중 방어(Defense-in-depth)다 - 비용 없이 안전을 하나 더 얹는다.</p>
     */
    @Modifying
    @Query("UPDATE SourceDocumentEntity e SET e.indexStatus = :indexStatus, e.indexReason = :indexReason "
            + "WHERE e.id = :documentId AND e.sourceVersion = :expectedSourceVersion AND e.indexStatus <> 'INDEXED'")
    int updateIndexStatusIfCurrent(@Param("documentId") Long documentId, @Param("indexStatus") String indexStatus,
            @Param("indexReason") String indexReason, @Param("expectedSourceVersion") String expectedSourceVersion);

    /**
     * M06이 추가한 행 단위 비관적 쓰기 Lock({@code SELECT ... FOR UPDATE})
     * 조회 - 원래 목적은 {@code ContentExtractionService.finalizePublish}가
     * 발행 직전 재검증(ACTIVE 상태 등)과 실제 발행 사이에 동시
     * {@code SourceConnectionService.disconnect}(Bulk UPDATE)가 끼어드는
     * 경쟁을 막는 것이었다.
     *
     * <p><b>M07A 교정 - 당시 상태:</b> V006이 {@code document_extracted_content}를
     * 제거하면서 {@code ContentExtractionService}의 Claim/Fetch/Parse/Publish
     * 로직 전체({@code finalizePublish} 포함)가 함께 제거돼, 그 시점에는 이
     * 메서드를 호출하는 곳이 없었다. Lock 자체의 의미(같은 {@code
     * source_documents} 행을 두고 경쟁하는 다른 Writer를 막는다는 것, {@code
     * source_connections}는 여기서 Lock을 걸지 않으므로 Lock 순서 역전 위험이
     * 없다는 것)는 그대로 유효하다.</p>
     *
     * <p><b>M10B 보안 교정 - 실제 호출자가 생겼다:</b> {@code
     * SourceSharingService.createShare}/{@code adminSetBlocked}가 이제 이
     * 메서드로 대상 문서 행을 잠근 뒤 {@code document_share_restrictions}(V011)를
     * 읽고 쓴다 - 같은 문서에 대한 "관리자 차단"과 "게시자의 Unshare 후 재게시"가
     * 서로 경쟁해 관리자 차단이 새 공유에 반영되지 않고 사라지는 것(Lost
     * Update)을 막기 위해서다.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM SourceDocumentEntity e WHERE e.id = :documentId")
    Optional<SourceDocumentEntity> findByIdForUpdate(@Param("documentId") Long documentId);

    /**
     * M06/M07A - Source Disconnect(논리적 삭제) 시 이 Source에 속한 모든
     * 문서의 Embedding Index 행을 제거한다. {@code markAllActiveAsDeletedForSource}
     * (Bulk UPDATE)는 FK {@code ON DELETE CASCADE}를 발동시키지 않으므로
     * 별도로 호출해야 한다 - 클래스 Javadoc의 의존 방향 설명 참고. (M07A
     * 교정: V006 이전에는 {@code document_extracted_content}를 대상으로 했다
     * - 그 테이블은 V006이 제거했다.)
     */
    @Modifying
    @Query(value = "DELETE FROM document_embedding_index "
            + "WHERE document_id IN (SELECT id FROM source_documents WHERE source_id = :sourceId)",
            nativeQuery = true)
    int deleteEmbeddingIndexForSource(@Param("sourceId") Long sourceId);

    /**
     * M09A 신규 - {@link #deleteEmbeddingIndexForSource}와 정확히 같은 기법(테이블
     * 이름만으로 참조하는 Native Query, {@code com.sdv.rag.*} Java Import 없음)을
     * 문서 하나 단위로 좁힌 버전이다. Catalog Sync가 한 문서의 실제 Version
     * 변경(재색인 필요) 또는 삭제/접근상실/휴지통 전이를 감지했을 때, 그 문서에
     * 속한 낡은 Embedding Index 행만 제거한다 - Source 전체를 지우는
     * {@code deleteEmbeddingIndexForSource}(Disconnect 전용)와 달리 진행 중인
     * Sync가 다른 문서의 Embedding까지 건드리지 않는다.
     */
    @Modifying
    @Query(value = "DELETE FROM document_embedding_index WHERE document_id = :documentId", nativeQuery = true)
    int deleteEmbeddingIndexForDocument(@Param("documentId") Long documentId);

    /**
     * M10 신규(RAG-011 File Metadata Discovery) - Chunk/Embedding 존재 여부나
     * {@code index_status}와 무관하게(§2A.4: File Discovery는 {@code
     * document_chunks}에 의존하지 않는다), 인증된 사용자가 소유한 ACTIVE Google
     * Drive Source에 속한 ACTIVE 문서만 후보로 삼는다({@code findIndexEligibleIds}와
     * 달리 색인 완료 여부를 조건으로 쓰지 않는다). {@link Slice}를 반환해 Count
     * Query 없이 {@code pageSize + 1}행만 조회한다 - 원시 총계를 계산/노출하지
     * 않는다(요구사항: raw catalog totals 비노출). 정렬은 호출자가 넘긴
     * {@link Pageable}의 {@code Sort}를 그대로 따른다 - 허용 목록(name/modifiedAt)과
     * {@code id} Tie-breaker는 호출자({@code FileMetadataDiscoveryService})가
     * 보장한다. {@code namePattern}은 호출자가 이미 소문자로 변환하고 {@code %}/{@code _}/
     * {@code \}를 이스케이프해 넘겨야 한다.
     *
     * <p>각 선택적 필터는 {@code hasXxx}(항상 non-null Boolean) + 실제 값(필터가
     * 없을 때는 호출자가 임의의 non-null Sentinel 값을 넘긴다, 예: {@code
     * FileMetadataDiscoveryService}) 쌍으로 받는다 - {@code (:param IS NULL OR ...)}
     * 형태 대신인 이유: PostgreSQL/Hibernate가 어떤 named parameter를 한 Query 안에서
     * 두 번 이상 참조할 때, 그 값이 실제로 SQL {@code NULL}이면 (Java {@code Instant}
     * 등 일부 Type에서) 그 Bind Parameter의 Type을 {@code bytea}로 잘못 추론해
     * {@code cannot cast type bytea to timestamp} 등으로 실패하는 것을 실제로
     * 재현/확인했다(Hibernate 7 + PostgreSQL JDBC 조합). 이 필터들에는 실제 SQL
     * {@code NULL}을 절대 바인딩하지 않음으로써 그 결함 자체를 구조적으로 피한다.</p>
     */
    @Query("SELECT d FROM SourceDocumentEntity d JOIN SourceConnectionEntity c ON c.id = d.sourceId "
            + "WHERE c.ownerSubject = :ownerSubject AND c.type = 'GOOGLE_DRIVE' AND c.status = 'ACTIVE' "
            + "AND d.state = 'ACTIVE' "
            + "AND (:hasSourceId = false OR d.sourceId = :sourceId) "
            + "AND (:hasMimeType = false OR d.mimeType = :mimeType) "
            + "AND (:hasNamePattern = false OR LOWER(d.name) LIKE :namePattern ESCAPE '\\') "
            + "AND (:hasModifiedFrom = false OR d.modifiedAt >= :modifiedFrom) "
            + "AND (:hasModifiedTo = false OR d.modifiedAt <= :modifiedTo)")
    Slice<SourceDocumentEntity> searchDiscoverable(@Param("ownerSubject") String ownerSubject,
            @Param("hasSourceId") boolean hasSourceId, @Param("sourceId") Long sourceId,
            @Param("hasMimeType") boolean hasMimeType, @Param("mimeType") String mimeType,
            @Param("hasNamePattern") boolean hasNamePattern, @Param("namePattern") String namePattern,
            @Param("hasModifiedFrom") boolean hasModifiedFrom, @Param("modifiedFrom") Instant modifiedFrom,
            @Param("hasModifiedTo") boolean hasModifiedTo, @Param("modifiedTo") Instant modifiedTo,
            Pageable pageable);

    /**
     * M10B 신규(SHR-001 소유자 전용 비공개 Metadata 선택기, {@code GET
     * /api/sources/{id}/files}) - 정확히 이 {@code sourceId}를 소유한 요청자에게만
     * 그 Source의 ACTIVE 문서를 Bounded/Paginated로 보여준다. 이 결과를 보는 것 자체는
     * 공유/공개 검색(RAG-011)과 무관하다 - 여기 나타난다고 다른 사용자에게 노출되는
     * 것은 절대 아니다({@code SourceSharingService}가 공유를 명시적으로 만들어야만
     * {@code document_shares}를 통해 노출된다). {@code searchDiscoverable}과 같은
     * 이유로 Count Query 없는 {@link Slice}만 쓴다 - 원시 총계를 노출하지 않는다.
     */
    @Query("SELECT d FROM SourceDocumentEntity d JOIN SourceConnectionEntity c ON c.id = d.sourceId "
            + "WHERE c.ownerSubject = :ownerSubject AND d.sourceId = :sourceId "
            + "AND c.type = 'GOOGLE_DRIVE' AND c.status = 'ACTIVE' AND d.state = 'ACTIVE' "
            + "AND (:hasNamePattern = false OR LOWER(d.name) LIKE :namePattern ESCAPE '\\')")
    Slice<SourceDocumentEntity> findOwnedForPicker(@Param("ownerSubject") String ownerSubject,
            @Param("sourceId") Long sourceId, @Param("hasNamePattern") boolean hasNamePattern,
            @Param("namePattern") String namePattern, Pageable pageable);

    /**
     * M11 후속 교정(이 작업 지시사항의 1번, "connect a successful verified same-account
     * reconnect to bounded scheduling of fresh eligible work") - 정확히 이 {@code
     * sourceId}에 속하고, 지금 이 순간 활성(미철회)+관리자 미차단 공유가 있는 ACTIVE
     * 문서만 Bounded({@link Pageable}의 Page Size)로 반환한다. Disconnect가
     * {@code deleteEmbeddingIndexForSource}로 이 Source의 모든 Embedding을 이미
     * 지웠으므로, 재연결 시점에는 버전 비교 없이 "지금 자격이 있는 문서 전부"가
     * 곧 "다시 색인이 필요한 문서 전부"다. 철회된 공유/관리자 차단은 WHERE 절
     * 자체에서 제외된다("preserving restrictions and revoked shares") - 이 결과에
     * 나타난 문서만 {@code GoogleDriveOAuthService}가 새 {@code IndexRequestedEvent}로
     * 스케줄링한다. {@code DocumentShareEntity}는 이 Source 도메인 자신의 Entity이므로
     * (V010) 이 JPQL Join이 RAG 도메인을 전혀 참조하지 않는다(의존 방향 규칙 유지).
     */
    @Query("SELECT d FROM SourceDocumentEntity d "
            + "JOIN DocumentShareEntity s ON s.documentId = d.id "
            + "JOIN SourceConnectionEntity c ON c.id = d.sourceId "
            + "WHERE d.sourceId = :sourceId AND d.state = 'ACTIVE' "
            + "AND s.revokedAt IS NULL AND s.adminBlocked = false "
            + "AND c.type = 'GOOGLE_DRIVE' AND c.status = 'ACTIVE'")
    Slice<SourceDocumentEntity> findActivelySharedForReconnectScheduling(@Param("sourceId") Long sourceId,
            Pageable pageable);
}
