package com.sdv.rag.application;

import tools.jackson.databind.ObjectMapper;
import com.sdv.audit.application.AuditService;
import com.sdv.common.model.UserContext;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.policy.domain.PolicyDecision;
import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.repository.DocumentExtractedContentJpaRepository;
import com.sdv.source.application.SourceConnectorRegistry;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * M06 신규 - Content Processing의 Application Service. Python AI Service({@link
 * DocumentParsingClient})를 호출해 문서 텍스트를 추출하고, 결과를
 * {@code document_extracted_content}(V005)에 발행(Publish)한다.
 *
 * <h2>인가(Authorization)</h2>
 * <p>{@link #extract}는 Byte를 Fetch하기 전에 반드시 {@link EffectivePermissionService#evaluate}
 * (action {@code "VIEW"})를 먼저 호출한다 - M05가 확립한 유일한 중앙 판단
 * 경로를 그대로 재사용한다(M06이 별도 인가 경로를 만들지 않는다). 거부되면
 * Byte Fetch/Claim/Parsing/저장 중 어느 것도 일어나지 않는다 - 다른 사용자의
 * 상태/콘텐츠를 전혀 바꾸지 않는다. Kafka/Sync 등 자동화된 수집 경로가 어떤
 * {@link UserContext}로 이 Service를 호출할지는 이 작업의 범위 밖이다(의도적으로
 * 미룬다 - 여기서 임의의 시스템 계정 우회를 만들지 않는다).</p>
 *
 * <h2>동시성 - Claim/Finalize</h2>
 * <p>{@link #extract} 전체는 하나의 Transaction이 아니다 - 느린 외부 Python
 * 호출 동안 DB Connection/Transaction을 잡아두지 않기 위해서다. 짧은 내부
 * Transaction을 {@link TransactionTemplate}으로 직접 연다(이 Class의
 * {@code private} 메서드에 {@code @Transactional}을 붙이고 같은 Class 안에서
 * {@code this.method()}로 호출하면 Spring AOP Proxy가 Self-Invocation을
 * 가로채지 못해 Transaction이 전혀 걸리지 않는다 - 그래서 선언적
 * ({@code @Transactional}) 대신 명령형({@link TransactionTemplate})을
 * 의도적으로 사용한다):</p>
 * <ol>
 *   <li>{@link #claim} - {@link DocumentExtractedContentJpaRepository#tryClaim}로
 *       이 문서에 대한 배타적 처리 권한을 원자적으로 얻는다. 실패하면(다른
 *       시도가 진행 중) 즉시 {@link ExtractionOutcome.Kind#CONFLICT}로
 *       끝낸다 - Byte Fetch/Parsing을 시도조차 하지 않는다.</li>
 *   <li>(Transaction 밖) Byte Fetch({@link DocumentSourceConnector#fetchContent})
 *       + Python {@code /parse} 호출.</li>
 *   <li>{@link #finalizePublish} - 성공이든 실패든 이 한 메서드가 발행/무효화를
 *       모두 최종 처리한다({@code SourceDocumentJpaRepository.findByIdForUpdate}로
 *       행 단위 Lock을 잡은 뒤 재검증 + 발행/무효화가 같은 Transaction
 *       안에서 원자적으로 일어난다 - "읽고 나서 Lock 없이 UPDATE"가 아니다).</li>
 * </ol>
 *
 * <p><b>Claim Lease(90초)의 정직한 한계(M06 후속 교정):</b> {@code
 * CLAIM_STALE_AFTER_SECONDS}는 60초 Parser 실행 상한(Python 쪽 Timeout)만
 * 감안해 계산했다 - {@link DocumentSourceConnector#fetchContent}는 이
 * Milestone에서 실제 구현체가 없고(M07/M08 예정), 그 호출 자체는 이 Lease
 * 계산에 포함되지 않는 무한정(Unbounded) 구간이다. 즉 이 Lease 하나만으로
 * "문서당 실행 중인 작업이 항상 하나"를 완전히 보장한다고 주장하지 않는다 -
 * 실제 Connector가 자기 자신의 Fetch Timeout을 이 Lease보다 충분히 짧게
 * 강제하지 않으면, 비정상적으로 느린 Fetch 도중 Claim이 Stale로 판정되어
 * 다른 시도가 재점유할 수 있다. 이는 M06이 새로 해결하는 문제가 아니라,
 * 향후 Connector 구현이 반드시 지켜야 할 전제 조건으로 명시적으로 남겨둔다
 * (여기서 이를 해결하려고 별도 범용 Job Platform을 만들지 않는다).</p>
 *
 * <h2>M06→M11 유효성 계약(문서화만, M11 미구현)</h2>
 * <p>{@code document_extracted_content} 행이 "현재 사용 가능한 결과"인
 * 조건은: {@code published_at IS NOT NULL} 이고, 그 행의 {@code source_version}이
 * {@code source_documents.source_version}(현재 값)과 같고(둘 다 Null이 아니고
 * 동일), {@code source_documents.state = 'ACTIVE'}. 이 판단은 "암묵적
 * 비교"에만 기대지 않는다: 인가된(Claim을 여전히 소유한) 현재 시도가
 * 실패/미지원/텍스트 없음으로 끝나거나 발행 직전 재검증에 실패하면(같은
 * 버전에 대한 재시도라도) {@link #finalizePublish}가 이전 발행 결과 전체를
 * 명시적으로 지운다(M06 후속 교정 - {@code invalidateIfAttemptStillOwned})
 * - 더 이상 낡은 텍스트가 "우연히 버전이 같아서" 유효한 것처럼 남지 않는다.
 * 이 계약을 실제로 읽는 M11 코드는 이 작업에서 구현하지 않는다.</p>
 */
@Service
public class ContentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ContentExtractionService.class);

    private static final String VIEW_ACTION = "VIEW";
    private static final String ACTIVE_STATE = "ACTIVE";
    private static final String PENDING_INDEX_STATUS = "PENDING";
    private static final String AUDIT_ACTION = "CONTENT_EXTRACTED";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILURE = "FAILURE";

    /** Claim 방치(Crash 등) 판단 임계값 - 60s Parser 실행 상한보다 여유 있게 크다(Fetch 단계는 포함하지 않는다 - 클래스 Javadoc 참고). */
    private static final long CLAIM_STALE_AFTER_SECONDS = 90;

    /** PARSE-한도: 원본 Byte 상한(25MB) - Byte Fetch 직후, Parser 호출 전에 즉시 거부한다. */
    private static final long MAX_INPUT_BYTES = 25L * 1024 * 1024;

    private static final int MAX_INDEX_REASON_LENGTH = 300;

    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final SourceConnectorRegistry sourceConnectorRegistry;
    private final DocumentExtractedContentJpaRepository extractedContentJpaRepository;
    private final DocumentParsingClient documentParsingClient;
    private final ContentProcessingPolicy contentProcessingPolicy;
    private final EffectivePermissionService effectivePermissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ContentExtractionService(
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceConnectorRegistry sourceConnectorRegistry,
            DocumentExtractedContentJpaRepository extractedContentJpaRepository,
            DocumentParsingClient documentParsingClient,
            ContentProcessingPolicy contentProcessingPolicy,
            EffectivePermissionService effectivePermissionService,
            AuditService auditService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.sourceConnectorRegistry = sourceConnectorRegistry;
        this.extractedContentJpaRepository = extractedContentJpaRepository;
        this.documentParsingClient = documentParsingClient;
        this.contentProcessingPolicy = contentProcessingPolicy;
        this.effectivePermissionService = effectivePermissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ExtractionOutcome extract(UserContext user, Long documentId) {
        PolicyDecision decision = effectivePermissionService.evaluate(user, documentId, VIEW_ACTION, null);
        if (decision.isDenied()) {
            return ExtractionOutcome.denied(decision.reasonCode().name());
        }

        UUID attemptId = UUID.randomUUID();
        Instant attemptStartedAt = Instant.now();
        if (!claim(documentId, attemptId, attemptStartedAt)) {
            log.info("Content extraction claim conflict for documentId={}", documentId);
            return ExtractionOutcome.conflict();
        }

        FetchPlan plan = loadFetchPlan(documentId);
        if (plan == null) {
            // Content 처리와 무관한 이유(Connector 없음/이미 비활성 등) - Claim만
            // 해제한다. 이미 발행되어 있던 결과(있다면)는 이 사유로는 무효화하지
            // 않는다(우리가 이 시도에서 콘텐츠 처리 자체를 시작조차 못했다).
            releaseClaim(documentId, attemptId);
            return ExtractionOutcome.rejected("document or source not active");
        }

        byte[] content;
        try {
            content = plan.connector().fetchContent(plan.sourceId(), plan.sourceDocumentId());
        } catch (RuntimeException e) {
            invalidateAndRecordFailure(documentId, attemptId, user, ParseOutcomeKind.FAILED, "content fetch failed");
            return ExtractionOutcome.failed("content fetch failed");
        }
        if (content == null || content.length == 0) {
            invalidateAndRecordFailure(documentId, attemptId, user, ParseOutcomeKind.NO_TEXT, "empty input");
            return ExtractionOutcome.failed("empty input");
        }
        if (content.length > MAX_INPUT_BYTES) {
            invalidateAndRecordFailure(documentId, attemptId, user, ParseOutcomeKind.FAILED,
                    "input size exceeds limit");
            return ExtractionOutcome.failed("input size exceeds limit");
        }

        String contentHash = sha256Hex(content);
        ParseOutcome outcome = documentParsingClient.parse(content, plan.name(), plan.mimeType());

        if (outcome.kind() == ParseOutcomeKind.SUCCESS) {
            return finalizePublish(documentId, attemptId, plan.expectedSourceVersion(), contentHash, outcome, user);
        }

        invalidateAndRecordFailure(documentId, attemptId, user, outcome.kind(), outcome.reason());
        return ExtractionOutcome.failed(outcome.reason());
    }

    private FetchPlan loadFetchPlan(Long documentId) {
        Optional<SourceDocumentEntity> maybeDocument = sourceDocumentJpaRepository.findById(documentId);
        if (maybeDocument.isEmpty() || !ACTIVE_STATE.equals(maybeDocument.get().getState())) {
            return null;
        }
        SourceDocumentEntity document = maybeDocument.get();
        Optional<SourceConnectionEntity> maybeConnection =
                sourceConnectionJpaRepository.findById(document.getSourceId());
        if (maybeConnection.isEmpty()
                || !SourceConnection.STATUS_ACTIVE.equals(maybeConnection.get().getStatus())) {
            return null;
        }
        SourceConnectionEntity connection = maybeConnection.get();
        SourceType type;
        try {
            type = SourceType.valueOf(connection.getType());
        } catch (IllegalArgumentException e) {
            return null;
        }
        Optional<DocumentSourceConnector> connector = sourceConnectorRegistry.getConnector(type);
        if (connector.isEmpty()) {
            return null;
        }
        return new FetchPlan(connector.get(), document.getSourceId(), document.getSourceDocumentId(),
                document.getName(), document.getMimeType(), document.getSourceVersion());
    }

    /** 짧은 Transaction - 원자적 조건부 Claim만 수행한다({@code true}가 반환되면 성공). */
    private boolean claim(Long documentId, UUID attemptId, Instant startedAt) {
        Instant staleBefore = startedAt.minusSeconds(CLAIM_STALE_AFTER_SECONDS);
        Boolean claimed = transactionTemplate.execute(status -> {
            int rows = extractedContentJpaRepository.tryClaim(documentId, attemptId, startedAt, staleBefore);
            return rows == 1;
        });
        return Boolean.TRUE.equals(claimed);
    }

    /**
     * 짧은 Transaction - 발행 직전 재검증(ACTIVE 상태/{@code source_version}
     * 스냅샷 동일성)과 실제 발행(또는 그 재검증 실패에 따른 무효화)을 하나의
     * Transaction 안에서 원자적으로 처리한다.
     *
     * <p><b>M06 후속 교정 - 행 단위 Lock:</b> {@code source_documents} 행을
     * {@link SourceDocumentJpaRepository#findByIdForUpdate}(비관적 쓰기 Lock)로
     * 읽는다 - 평범한 {@code findById} 뒤에 별도 {@code UPDATE}를 하는 것만으로는
     * "이 재검증 읽기"와 "실제 발행 UPDATE" 사이에 동시 {@code SourceConnectionService.disconnect}
     * (같은 행을 바꾸는 Bulk UPDATE)가 끼어들 가능성을 막지 못한다 - 이제는
     * Lock을 잡은 행이 재검증부터 발행까지 같은 Transaction 안에서 유지되므로,
     * Disconnect의 해당 행 변경은 이 Transaction이 끝날 때까지 기다린다.</p>
     *
     * <p><b>재검증 실패 시:</b> Claim을 여전히 우리가 소유하고 있다면(더 새로운
     * 시도가 재점유하지 않았다면) {@link #invalidateAndRecordFailure}로 즉시
     * Claim을 해제하고 실패를 기록한다 - 이전에는 아무것도 하지 않고 그냥
     * {@code SUPERSEDED}만 반환해, 다음 재시도가 최대 90초 동안 막혔다.</p>
     *
     * <p><b>성공 시:</b> 발행과 함께 {@code index_status}를 {@code PENDING}으로
     * (Reason은 {@code null}로) 재설정한다 - 이전에는 이 컬럼을 건드리지 않아,
     * 예전에 {@code FAILED}였던 문서가 성공적으로 재추출돼도 {@code FAILED}로
     * 남아있는 결함이 있었다(추출 성공이 곧 {@code INDEXED}는 아니라는 계약은
     * 그대로 지킨다 - {@code INDEXED}를 쓰지 않는다).</p>
     */
    private ExtractionOutcome finalizePublish(Long documentId, UUID attemptId, String expectedSourceVersion,
            String contentHash, ParseOutcome outcome, UserContext user) {
        FinalizeResult result = transactionTemplate.execute(status -> {
            Optional<SourceDocumentEntity> maybeDocument = sourceDocumentJpaRepository.findByIdForUpdate(documentId);
            if (maybeDocument.isEmpty() || !ACTIVE_STATE.equals(maybeDocument.get().getState())) {
                return FinalizeResult.preconditionFailed("document not active");
            }
            SourceDocumentEntity document = maybeDocument.get();
            Optional<SourceConnectionEntity> maybeConnection =
                    sourceConnectionJpaRepository.findById(document.getSourceId());
            if (maybeConnection.isEmpty()
                    || !SourceConnection.STATUS_ACTIVE.equals(maybeConnection.get().getStatus())) {
                return FinalizeResult.preconditionFailed("source not active");
            }
            String currentVersion = document.getSourceVersion();
            if (currentVersion == null || expectedSourceVersion == null
                    || !currentVersion.equals(expectedSourceVersion)) {
                // Snapshot 동일성을 증명할 수 없다(Null 포함) - Fail Closed.
                return FinalizeResult.preconditionFailed("source version could not be verified");
            }

            String locationsJson = writeLocationsJson(outcome);
            Instant publishedAt = Instant.now();
            int rows = extractedContentJpaRepository.publishIfAttemptStillOwned(documentId, attemptId,
                    currentVersion, contentHash, outcome.parserName(), outcome.parserVersion(),
                    outcome.normalizationVersion(), outcome.normalizedText(), locationsJson, publishedAt);
            if (rows != 1) {
                return FinalizeResult.claimLost();
            }
            // 성공 - 낡은 index_status(FAILED/INDEXED 등)를 남겨두지 않는다.
            sourceDocumentJpaRepository.updateIndexStatus(documentId, PENDING_INDEX_STATUS, null);
            auditService.record(user.subject(), AUDIT_ACTION, "document:" + documentId, SUCCESS,
                    ParseOutcomeKind.SUCCESS.name(), Map.of());
            return FinalizeResult.published();
        });

        if (result.isPublished()) {
            return ExtractionOutcome.success();
        }
        if (result.isClaimLost()) {
            // 이미 다른(더 새로운) 시도가 이 문서를 재점유했다 - 그 시도의 상태를
            // 절대 건드리지 않는다.
            return ExtractionOutcome.supersededOrConflict();
        }
        // 재검증 실패 - Claim을 여전히 우리가 소유하고 있었다면 여기서 즉시
        // 해제+무효화+실패 기록까지 끝낸다(Stale 판정까지 기다리지 않는다).
        invalidateAndRecordFailure(documentId, attemptId, user, ParseOutcomeKind.FAILED, result.reason());
        return ExtractionOutcome.failed(result.reason());
    }

    /**
     * 짧은 Transaction - 인가된(Claim을 여전히 소유한) 현재 시도가 실패/미지원/
     * 텍스트 없음으로 끝났을 때 호출한다. Claim을 여전히 우리가 소유하고
     * 있었다면(반환값 {@code 1}) 이전에 발행되어 있던 결과 전체를 명시적으로
     * 무효화하고({@link DocumentExtractedContentJpaRepository#invalidateIfAttemptStillOwned})
     * {@code index_status}/{@code index_reason}을 기록한다 - 이미 다른
     * 시도가 재점유했다면(반환값 {@code 0}) 아무것도 하지 않는다("더 새로운
     * 시도의 Claim/상태를 절대 건드리지 않는다").
     *
     * <p><b>M06 후속 교정 - Lock 순서 통일(Deadlock 제거):</b> {@code
     * source_documents} 행을 {@link SourceDocumentJpaRepository#findByIdForUpdate}로
     * 먼저 잠근 뒤에야 {@code document_extracted_content}를 바꾼다 -
     * {@link #finalizePublish}, {@code SourceConnectionService.disconnect}와
     * 정확히 같은 순서다. 이전에는 이 메서드가 {@code document_extracted_content}를
     * 먼저 바꾸고 그 다음 {@code source_documents}를 바꿔, 반대 순서로 두
     * 테이블을 잠그는 {@code disconnect}의 같은 Transaction과 Lock 순환을
     * 만들 수 있었다(각자 상대가 쥔 행을 요구 - Postgres Deadlock). 여기서는
     * Lock 확보 자체가 목적이므로 조회 결과는 쓰지 않는다.</p>
     */
    private void invalidateAndRecordFailure(Long documentId, UUID attemptId, UserContext user,
            ParseOutcomeKind kind, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            sourceDocumentJpaRepository.findByIdForUpdate(documentId);

            int rows = extractedContentJpaRepository.invalidateIfAttemptStillOwned(documentId, attemptId);
            if (rows != 1) {
                return;
            }
            String boundedReason = boundedReason(reason);
            contentProcessingPolicy.classify(kind)
                    .ifPresent(indexStatus -> sourceDocumentJpaRepository.updateIndexStatus(documentId,
                            indexStatus.name(), boundedReason));
            auditService.record(user.subject(), AUDIT_ACTION, "document:" + documentId, FAILURE, kind.name(),
                    Map.of());
        });
    }

    /** Content 처리와 무관한 사유로 Claim만 해제한다 - 발행된 결과가 있다면 손대지 않는다. */
    private void releaseClaim(Long documentId, UUID attemptId) {
        transactionTemplate.executeWithoutResult(
                status -> extractedContentJpaRepository.releaseClaim(documentId, attemptId));
    }

    private String writeLocationsJson(ParseOutcome outcome) {
        try {
            return objectMapper.writeValueAsString(outcome.locations());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize extracted locations");
        }
    }

    private static String boundedReason(String reason) {
        if (reason == null) {
            return null;
        }
        String singleLine = reason.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() > MAX_INDEX_REASON_LENGTH
                ? singleLine.substring(0, MAX_INDEX_REASON_LENGTH)
                : singleLine;
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private record FetchPlan(DocumentSourceConnector connector, Long sourceId, String sourceDocumentId, String name,
            String mimeType, String expectedSourceVersion) {
    }

    private record FinalizeResult(Kind kind, String reason) {

        private enum Kind {
            PUBLISHED,
            CLAIM_LOST,
            PRECONDITION_FAILED
        }

        boolean isPublished() {
            return kind == Kind.PUBLISHED;
        }

        boolean isClaimLost() {
            return kind == Kind.CLAIM_LOST;
        }

        static FinalizeResult published() {
            return new FinalizeResult(Kind.PUBLISHED, null);
        }

        static FinalizeResult claimLost() {
            return new FinalizeResult(Kind.CLAIM_LOST, null);
        }

        static FinalizeResult preconditionFailed(String reason) {
            return new FinalizeResult(Kind.PRECONDITION_FAILED, reason);
        }
    }

    public record ExtractionOutcome(Kind kind, String detail) {

        public enum Kind {
            SUCCESS,
            DENIED,
            CONFLICT,
            SUPERSEDED,
            REJECTED,
            FAILED
        }

        static ExtractionOutcome success() {
            return new ExtractionOutcome(Kind.SUCCESS, null);
        }

        static ExtractionOutcome denied(String reasonCode) {
            return new ExtractionOutcome(Kind.DENIED, reasonCode);
        }

        static ExtractionOutcome conflict() {
            return new ExtractionOutcome(Kind.CONFLICT, "extraction already in progress");
        }

        static ExtractionOutcome supersededOrConflict() {
            return new ExtractionOutcome(Kind.SUPERSEDED, "result superseded before publish");
        }

        static ExtractionOutcome rejected(String detail) {
            return new ExtractionOutcome(Kind.REJECTED, detail);
        }

        static ExtractionOutcome failed(String detail) {
            return new ExtractionOutcome(Kind.FAILED, detail);
        }
    }
}
