package com.sdv.rag.infrastructure.persistence.repository;

import com.sdv.rag.infrastructure.persistence.entity.DocumentExtractedContentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * F-BE (M06 신규). {@code document_extracted_content}(V005) Persistence -
 * 문서당 배타적 처리 권한(Claim)과 발행(Publish)을 원자적 조건부 SQL로만
 * 수행한다.
 *
 * <p><b>왜 원자적 조건부 SQL인가:</b> "행을 읽고 - Java에서 조건을 판단하고 -
 * 다시 저장한다" 방식은 두 요청이 동시에 같은 판단(둘 다 "Claim되지 않음")을
 * 내린 뒤 모두 저장을 시도할 수 있다(Check-Then-Save Race). {@link #tryClaim}/
 * {@link #publishIfAttemptStillOwned}는 판단(WHERE)과 실행(UPDATE/INSERT)을
 * 하나의 SQL 문으로 묶어, Postgres가 그 행에 대해 제공하는 원자성에 기대는
 * 방식이다.</p>
 *
 * <p><b>느린 외부 Parsing 호출 동안 DB Transaction/Lock을 잡지 않는다:</b>
 * {@link #tryClaim}과 {@link #publishIfAttemptStillOwned}는 각각 독립된 짧은
 * Transaction(호출자 {@code ContentExtractionService}가 관리)이며, 그 사이의
 * 느린 Python AI Service 호출 동안에는 어떤 Connection/Transaction도 열려있지
 * 않다.</p>
 *
 * <p><b>여러 Backend Process/Pod에서도 안전하다:</b> 이 원자성은 JVM 안의
 * Lock(예: {@code synchronized})이 아니라 Postgres 자체의 행 단위 원자적
 * 조건부 쓰기에서 나온다 - Backend가 여러 Instance로 떠 있어도 동일하게
 * 안전하다.</p>
 */
public interface DocumentExtractedContentJpaRepository extends JpaRepository<DocumentExtractedContentEntity, Long> {

    /**
     * 이 문서에 대한 배타적 처리 권한을 원자적으로 얻으려 시도한다. 행이 없으면
     * 새로 만들고, 있으면 "현재 Claim이 없거나({@code attempt_id IS NULL})
     * Claim이 {@code staleBefore}보다 오래됐을 때만"(방치된/Crash된 이전
     * 시도의 재점유) 덮어쓴다. 반환값이 {@code 1}이면 Claim 성공(이 호출자가
     * {@code attemptId}의 소유자다), {@code 0}이면 다른 시도가 이미 진행
     * 중이므로 실패다(이 경우 아무것도 바뀌지 않는다 - 발행된 결과가 있었다면
     * 그대로 남는다).
     */
    @Modifying
    @Query(value = "INSERT INTO document_extracted_content (document_id, attempt_id, attempt_started_at) "
            + "VALUES (:documentId, :attemptId, :startedAt) "
            + "ON CONFLICT (document_id) DO UPDATE "
            + "SET attempt_id = EXCLUDED.attempt_id, attempt_started_at = EXCLUDED.attempt_started_at "
            + "WHERE document_extracted_content.attempt_id IS NULL "
            + "OR document_extracted_content.attempt_started_at < :staleBefore",
            nativeQuery = true)
    int tryClaim(@Param("documentId") Long documentId, @Param("attemptId") UUID attemptId,
            @Param("startedAt") Instant startedAt, @Param("staleBefore") Instant staleBefore);

    /**
     * 성공한 추출 결과를 원자적으로 발행(Publish)한다 - {@code document_id}와
     * {@code attempt_id}가 여전히 이 호출자가 {@link #tryClaim}에서 받은 값과
     * 정확히 일치할 때만 적용된다(이 사이에 Claim이 Stale 판정되어 다른 시도가
     * 재점유했다면 이 UPDATE는 0행에 적용되고 아무것도 바뀌지 않는다 - 뒤늦은
     * 성공이 더 새로운 시도의 결과를 덮어쓰지 않는다). 성공 시 Claim
     * 컬럼도 함께 비운다. 반환값이 {@code 1}이어야 발행이 실제로 반영된
     * 것이다.
     */
    @Modifying
    @Query(value = "UPDATE document_extracted_content SET "
            + "source_version = :sourceVersion, content_hash = :contentHash, parser_name = :parserName, "
            + "parser_version = :parserVersion, normalization_version = :normalizationVersion, "
            + "normalized_text = :normalizedText, locations = :locationsJson, published_at = :publishedAt, "
            + "attempt_id = NULL, attempt_started_at = NULL "
            + "WHERE document_id = :documentId AND attempt_id = :attemptId",
            nativeQuery = true)
    int publishIfAttemptStillOwned(@Param("documentId") Long documentId, @Param("attemptId") UUID attemptId,
            @Param("sourceVersion") String sourceVersion, @Param("contentHash") String contentHash,
            @Param("parserName") String parserName, @Param("parserVersion") String parserVersion,
            @Param("normalizationVersion") String normalizationVersion,
            @Param("normalizedText") String normalizedText, @Param("locationsJson") String locationsJson,
            @Param("publishedAt") Instant publishedAt);

    /**
     * Content 처리와 무관한 이유(예: Connector 없음 - {@code REJECTED})로 끝난
     * 시도의 Claim만 원자적으로 해제한다 - 발행되어 있던 결과(있다면)는
     * 손대지 않는다. {@code attempt_id}가 여전히 일치할 때만 적용된다.
     * "현재 콘텐츠 처리 시도가 실패했다"는 경우에는 이 메서드가 아니라
     * {@link #invalidateIfAttemptStillOwned}를 쓴다(M06 후속 교정 - 아래
     * 참고).
     */
    @Modifying
    @Query(value = "UPDATE document_extracted_content SET attempt_id = NULL, attempt_started_at = NULL "
            + "WHERE document_id = :documentId AND attempt_id = :attemptId",
            nativeQuery = true)
    int releaseClaim(@Param("documentId") Long documentId, @Param("attemptId") UUID attemptId);

    /**
     * M06 후속 교정. 인가된(Claim을 여전히 소유한) 현재 시도가 실패/미지원/
     * 텍스트 없음으로 끝났거나, 발행 직전 재검증(ACTIVE 상태/{@code source_version}
     * 동일성)에 실패했을 때 호출한다 - Claim을 해제할 뿐 아니라, 이전에
     * 발행되어 있던 결과가 있었다면 그 결과 전체를 명시적으로 무효화한다
     * (발행 컬럼 전부를 NULL로 되돌린다 - V005 CHECK 제약의 "미발행" 분기와
     * 정확히 일치하는 상태다). {@code published_at IS NOT NULL AND
     * source_version = 현재값 AND ACTIVE}라는 암묵적 비교만으로는 "같은
     * 버전에 대한 재시도 실패" 상황에서 이전 결과가 여전히 유효한 것처럼
     * 보일 수 있다는 결함을 고친다 - 이제는 명시적으로 지운다.
     *
     * <p>{@code attempt_id}가 여전히 일치할 때만(반환값 {@code 1}) 적용된다 -
     * 이미 다른 시도가 이 문서를 재점유했다면(반환값 {@code 0}) 아무것도
     * 바뀌지 않는다("더 새로운 시도의 결과를 지우지 않는다").</p>
     */
    @Modifying
    @Query(value = "UPDATE document_extracted_content SET "
            + "attempt_id = NULL, attempt_started_at = NULL, "
            + "source_version = NULL, content_hash = NULL, parser_name = NULL, parser_version = NULL, "
            + "normalization_version = NULL, normalized_text = NULL, locations = NULL, published_at = NULL "
            + "WHERE document_id = :documentId AND attempt_id = :attemptId",
            nativeQuery = true)
    int invalidateIfAttemptStillOwned(@Param("documentId") Long documentId, @Param("attemptId") UUID attemptId);
}
