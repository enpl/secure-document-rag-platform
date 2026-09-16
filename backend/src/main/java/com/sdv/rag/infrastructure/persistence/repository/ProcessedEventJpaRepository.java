package com.sdv.rag.infrastructure.persistence.repository;

import com.sdv.rag.infrastructure.persistence.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M11 신규(V012). {@code processed_events} Persistence -
 * {@link com.sdv.rag.infrastructure.event.IndexRequestedConsumer}의 멱등성 확인을
 * 제공한다. {@link com.sdv.rag.application.IndexBackfillService}의 "지금 현재
 * Generation이 아직 유효하게 색인되지 않은 활성 공유" 조회({@link
 * #findSharesNeedingBackfill})는 M11 후속 교정으로 더 이상 이 Ledger 자체를
 * 근거로 쓰지 않는다(그 Method의 Javadoc 참고) - Interface 이름/위치만 그대로 둔다.
 */
public interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEventEntity, ProcessedEventEntity.Key> {

    /**
     * M11 신규, M11 후속 교정({@code IndexBackfillService}/{@code
     * ProcessedEventJpaRepository} 정확성 교정, 이 작업 지시사항 1번) - {@link
     * com.sdv.rag.application.IndexBackfillService} 전용, 경계 있는(Bounded) 조회.
     *
     * <h2>교정 전 결함 - "한 번 INDEXED였다"는 "지금 현재 Generation이 유효하다"의
     * 증거가 아니다</h2>
     * <p>이전 버전은 {@code processed_events}에 이 문서를 {@code INDEXED}로 기록한
     * 행이 과거에 단 한 번이라도 있었는지만 확인했다 - 그러나 그 뒤 (1) unshare/관리자
     * 차단이 {@code deleteEmbeddingIndexForDocument}로 실제 Embedding 행을 지웠거나,
     * (2) Content가 새 Version으로 바뀌어 그 낡은 Generation이 더 이상 현재 Content를
     * 대표하지 않을 수 있는데도, 이 조회는 그 문서를 영구히 "이미 끝남"으로 취급해
     * 다시는 Backfill 후보로 올리지 않았다. 이제는 과거 이력이 아니라, {@code
     * document_embedding_index}에 "지금 이 문서의 현재 {@code source_version}과
     * 정확히 일치하는" 행이 실제로 존재하는지를 직접 확인한다 - 존재하지 않으면(한
     * 번도 색인된 적 없음, 삭제됨, 또는 낡은 Version으로만 색인됨 모두 포함) 후보에
     * 포함된다. 더 이상 {@code processed_events}를 참조하지 않으므로 {@code
     * consumerName} 인자도 필요 없다.</p>
     *
     * <p>다른 Domain의 {@code document_shares}/{@code source_connections}/{@code
     * source_documents}/{@code document_embedding_index} Java Entity를 Import하지
     * 않고 Table 이름만으로 참조하는 Native Query다({@code
     * SourceDocumentJpaRepository}가 이미 확립한 관례와 동일, RAG -> Source 방향
     * 참조만 허용된다). {@code document_shares.id} Keyset Pagination(마지막으로 본
     * ID보다 큰 것만)이라 반복 호출해도 이미 확인한 구간을 다시 스캔하지 않는다 -
     * 재실행해도 안전(멱등)하다.</p>
     */
    @Query(value = "SELECT s.id AS shareId, s.document_id AS documentId FROM document_shares s "
            + "JOIN source_connections c ON c.id = s.source_id "
            + "JOIN source_documents d ON d.id = s.document_id "
            + "WHERE s.revoked_at IS NULL AND s.admin_blocked = false "
            + "AND c.type = 'GOOGLE_DRIVE' AND c.status = 'ACTIVE' "
            + "AND d.state = 'ACTIVE' "
            + "AND s.id > :afterShareId "
            + "AND NOT EXISTS (SELECT 1 FROM document_embedding_index e "
            + "    WHERE e.document_id = s.document_id AND e.source_version = d.source_version) "
            + "ORDER BY s.id "
            + "LIMIT :batchSize",
            nativeQuery = true)
    List<BackfillCandidate> findSharesNeedingBackfill(@Param("afterShareId") long afterShareId,
            @Param("batchSize") int batchSize);

    /** {@link #findSharesNeedingBackfill}의 한 행 - Cursor 전진용 {@code shareId}와 이벤트 발행용 {@code documentId}. */
    interface BackfillCandidate {
        Long getShareId();

        Long getDocumentId();
    }

    /**
     * M11 후속 교정(2026-09-16, 이번 작업 지시사항 C) - "Preserve completion history
     * under duplicates and partial failure." 이전에는 {@code
     * IndexRequestedConsumer.recordProcessed}/종결 실패 기록 둘 다 {@code
     * JpaRepository.save(new ProcessedEventEntity(...))}(할당된 복합 ID)를 썼다 -
     * ID가 이미 채워져 있으면 Hibernate의 {@code save()}는 {@code isNew()} 판단에
     * 따라 {@code merge()}를 호출하는데, 이는 실제 제약 위반이 (즉시 {@code
     * executeUpdate}가 아니라) Flush/Commit 시점에야 드러날 수 있어 "존재 확인 후
     * 저장"이 진짜 Write-Once 보장이 아니었다("An existence check followed by save
     * is not a write-once completion guarantee").
     *
     * <p>이 Method는 대신 Postgres {@code INSERT ... ON CONFLICT DO NOTHING}을
     * 직접 실행한다 - 새 Migration 없이 기존 {@code pk_processed_events(event_id,
     * consumer_name)} 제약만 사용한다. 이 문장 자체가 원자적이라, 두 Writer가 동시에
     * 같은 Key로 완료를 기록하려 해도 정확히 하나만 실제로 삽입되고 나머지는 예외
     * 없이 0행으로 끝난다 - "which exceptions are duplicates"를 더 이상 구분할 필요가
     * 없다(이 문장에서는 PK 충돌이 예외로 나타나지 않는다). 이미 다른 행(성공이든
     * 실패든)이 있으면 이 호출은 그 행을 절대 덮어쓰지 않는다 - "a late
     * terminal-failure attempt must not replace an existing successful disposition"를
     * 이 문장 하나가 구조적으로 보장한다(First-Writer-Wins).</p>
     *
     * @return 실제로 새로 삽입됐으면 1, 이미 같은 Key로 기록된 행이 있었으면 0.
     */
    @Modifying
    @Query(value = "INSERT INTO processed_events (event_id, consumer_name, outcome, reason_code, document_id, "
            + "processed_at) VALUES (:eventId, :consumerName, :outcome, :reasonCode, :documentId, :processedAt) "
            + "ON CONFLICT (event_id, consumer_name) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("consumerName") String consumerName,
            @Param("outcome") String outcome, @Param("reasonCode") String reasonCode,
            @Param("documentId") Long documentId, @Param("processedAt") Instant processedAt);
}
