package com.sdv.rag.application;

import com.sdv.common.model.UserContext;
import com.sdv.policy.application.AiUsagePolicyService;
import com.sdv.policy.domain.AiRequestContext;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.rag.domain.EmbeddingChunk;
import com.sdv.rag.domain.IndexOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.source.application.SourceConnectorRegistry;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceMetadataVerificationOutcome;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.domain.SourceType;
import com.sdv.source.domain.ShareAudience;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * M11 신규 - "resolve current eligibility -> bounded publisher-bound source fetch
 * -> isolated parse/chunk/embed -> fresh checks -> atomic generation publication ->
 * cleanup"을 담당하는 RAG Ingestion Orchestration Application Service(이 작업
 * 지시사항의 4번). {@code IndexRequestedConsumer}가 Kafka Listener Thread에서
 * 문서 하나당 한 번 {@link #processWithReasonCode(Long)}을 호출한다 - Google/Python 호출 도중
 * 어떤 DB Transaction/Lock도 열어두지 않는다({@link com.sdv.source.application.SharedFileDownloadService}
 * (M10C)와 동일한 원칙, "Do not hold database locks across Google/Python/model
 * calls").
 *
 * <h2>이벤트/Client 신원을 신뢰하지 않는다</h2>
 * <p>{@code documentId} 하나만 받는다 - Kafka {@code IndexRequestedEvent} Payload의
 * 다른 어떤 필드도 인가 증거로 쓰지 않는다. {@link #resolveEligibility}가 매번
 * {@code source_documents}/{@code document_shares}/{@code source_connections}를
 * 새로 조회해 "지금 이 순간" 자격이 있는지 처음부터 다시 판단한다.</p>
 *
 * <h2>Google Docs Export/Core 포맷 Allowlist를 재구현하지 않는다</h2>
 * <p>{@link DocumentSourceConnector#fetchContent}(M08)가 이미 Core 포맷
 * Allowlist/Google Docs Transient Export/Fetch 전후 Version 재검증을 수행한다 -
 * 이 Class는 그 결과만 소비한다. {@link #isPreClassifiedUnsupported}는 명백히
 * 알려진 미지원 형식(이미지/오디오/비디오/Archive/PPTX/XLSX 등)에 대해서만
 * Google 호출 자체를 건너뛰는 최선 노력(Best-effort) 최적화일 뿐이다 - 권위
 * 있는 판단은 항상 Connector 자신이 내린다("Pre-classify unsupported formats
 * before content fetch where metadata permits").</p>
 *
 * <h2>AI 색인 자격 - 새 ShareAction을 만들지 않는다</h2>
 * <p>{@code ShareAction.VIEW}는 File Metadata Discovery 권한일 뿐, "AI가 이
 * 파일로 답할 수 있다"는 뜻이 아니다(CLAUDE.md). 그렇다고 {@code DOWNLOAD}를
 * AI 동의로 치환하지도 않는다(이 작업 지시사항이 명시적으로 금지). 대신 이미
 * M05가 확립한 {@link AiUsagePolicyService#evaluate}(등급별 AI 허용 정책,
 * {@link AiRequestContext#local()} - Local Ollama Embedding이라 External이
 * 아니다)로 "이 등급의 문서를 AI가 처리해도 되는가"를 판단한다. 활성(미철회)+
 * 관리자 미차단 공유의 존재 자체는 "공통 발견/AI/다운로드 대상으로 명시적으로
 * 공유됐다"는 v1.5 B안의 최소 전제이며, 최종 Live Retrieval(M12)이 요청마다
 * 다시 재확인해야 할 몫은 여기서 대신 판단하지 않는다(§2A.4/§2A.5 - Embedding
 * Hit은 권한 증명도 근거도 아니다).</p>
 *
 * <h2>동시성 - 명시적 Lock 순서</h2>
 * <p>{@link #publishGeneration}은 {@code source_connections}(부모) → {@code
 * source_documents}(자식) → {@code document_shares}(M11 후속 교정 신규) 순서로
 * 잠근다 - {@code com.sdv.sync.application.SourceSyncPageWriter#applyPage}가 이미
 * 확립한 앞 두 단계 순서를 그대로 잇는다. {@code SourceConnectionService.disconnect}는
 * 자신의 {@code GoogleTokenService.revoke} 경로에서 이미 같은 {@code
 * source_connections} 행을 {@code SELECT ... FOR UPDATE}로 잠근다 - 그래서 이
 * Class가 그 뒤를 이어 같은 행을 잠그려 하면, 어느 쪽이 먼저 시작했든 한쪽이
 * 완전히 Commit할 때까지 다른 쪽이 대기한다(Postgres 자체의 행 Lock, MVP-18이
 * 요구하는 "새 Embedding Writer 대 disconnect 교차 경합" 검증 대상이 바로 이
 * 지점이다). {@code SourceSharingService.createShare}/{@code unshare}/{@code
 * adminSetBlocked}도 이 Class와 같은 {@code source_documents} 행을 먼저 잠근 뒤
 * 공유를 다룬다 - 세 Writer(공유 관리, disconnect, 이 Orchestrator) 모두 같은
 * 순서로만 잠그므로 Deadlock Cycle이 생기지 않는다. {@code updateShare}는
 * {@code source_documents}를 잠그지 않지만 {@code document_shares} 행 자체에
 * 대한 갱신은 이 Class가 새로 잡는 {@code document_shares} Lock 뒤에서 자연히
 * 대기한다(Postgres 행 Lock은 어느 쪽이 명시적으로 Lock을 요청했는지와 무관하게
 * 대칭적으로 적용된다).</p>
 */
@Service
public class IndexOrchestrator {

    /** V002/CLAUDE.md "Vector 결정 (Canonical)" - 변경 시 새 Migration + 사용자 승인 필요. */
    public static final String EXPECTED_EMBEDDING_MODEL = "bge-m3:567m";

    private static final String GOOGLE_DRIVE_TYPE = "GOOGLE_DRIVE";
    private static final String ACTIVE = "ACTIVE";
    private static final String INDEXED = "INDEXED";
    private static final String SKIPPED_UNSUPPORTED = "SKIPPED_UNSUPPORTED";
    private static final String SKIPPED_NO_TEXT = "SKIPPED_NO_TEXT";

    /**
     * 명백히 알려진 미지원 형식(Metadata Prefix)만 담는다 - Allowlist가 아니라
     * Blocklist다(모호하거나 알 수 없는 MIME은 절대 여기서 미리 거르지 않고
     * Connector의 권위 있는 판단에 맡긴다 - 오탐으로 정당한 PDF/DOCX/TXT/MD를
     * 건너뛰지 않기 위함).
     */
    private static final Set<String> KNOWN_UNSUPPORTED_MIME_PREFIXES = Set.of(
            "image/", "audio/", "video/",
            "application/zip", "application/x-7z-compressed", "application/x-rar",
            "application/vnd.openxmlformats-officedocument.presentationml",
            "application/vnd.openxmlformats-officedocument.spreadsheetml",
            "application/vnd.google-apps.spreadsheet", "application/vnd.google-apps.presentation",
            "application/vnd.google-apps.folder", "application/vnd.google-apps.shortcut",
            "application/x-msdownload", "application/x-executable");

    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final DocumentShareJpaRepository documentShareJpaRepository;
    private final DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository;
    private final SourceConnectorRegistry sourceConnectorRegistry;
    private final AiUsagePolicyService aiUsagePolicyService;
    private final DocumentParsingClient documentParsingClient;
    private final TransactionTemplate publishTransaction;
    private final Clock clock;

    @Autowired
    public IndexOrchestrator(SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            DocumentShareJpaRepository documentShareJpaRepository,
            DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository,
            SourceConnectorRegistry sourceConnectorRegistry, AiUsagePolicyService aiUsagePolicyService,
            DocumentParsingClient documentParsingClient, PlatformTransactionManager transactionManager) {
        this(sourceDocumentJpaRepository, sourceConnectionJpaRepository, documentShareJpaRepository,
                documentEmbeddingJpaRepository, sourceConnectorRegistry, aiUsagePolicyService, documentParsingClient,
                transactionManager, Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}을 주입하기 위한 패키지 전용 생성자. */
    IndexOrchestrator(SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            DocumentShareJpaRepository documentShareJpaRepository,
            DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository,
            SourceConnectorRegistry sourceConnectorRegistry, AiUsagePolicyService aiUsagePolicyService,
            DocumentParsingClient documentParsingClient, PlatformTransactionManager transactionManager, Clock clock) {
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.documentShareJpaRepository = documentShareJpaRepository;
        this.documentEmbeddingJpaRepository = documentEmbeddingJpaRepository;
        this.sourceConnectorRegistry = sourceConnectorRegistry;
        this.aiUsagePolicyService = aiUsagePolicyService;
        this.documentParsingClient = documentParsingClient;
        this.publishTransaction = new TransactionTemplate(transactionManager);
        this.publishTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * 문서 하나에 대한 색인 시도 전체. 정상적으로 끝나면(재시도가 필요 없는
     * 경우) {@link IndexProcessingOutcome}을 반환한다 - 재시도가 필요하면
     * {@link TransientIndexingException}을 던진다(호출자가 Bounded Retry/DLQ로
     * 넘긴다). 사유 코드까지 필요한 호출자는 {@link #processWithReasonCode}를 쓴다.
     */
    public IndexProcessingOutcome process(Long documentId) {
        return processWithReasonCode(documentId).outcome();
    }

    /**
     * M17 진단 교정(이 작업 지시사항 2번) - {@link #process}와 정확히 같은 판단
     * 로직이지만, {@code SKIPPED_INELIGIBLE}이 자격 검사/자격증명·원본 읽기/버전
     * 검증/최종 세대 검증 중 정확히 어느 단계에서 나왔는지 {@link IndexProcessingResult#reasonCode()}로
     * 함께 반환한다({@link IneligibilityStage} 참고). {@link
     * com.sdv.rag.infrastructure.event.IndexRequestedConsumer}가 이 값을 그대로
     * {@code processed_events.reason_code}에 옮겨 적는다 - 사후에 DB를 다시
     * 조회해 추측하지 않고, 이 판정을 내리는 그 자리에서 채운 값이다.
     */
    public IndexProcessingResult processWithReasonCode(Long documentId) {
        Eligibility eligibility = resolveEligibility(documentId);
        if (eligibility == null) {
            return ineligible(IneligibilityStage.ELIGIBILITY_CHECK);
        }
        SourceDocumentEntity document = eligibility.document();
        String capturedSourceVersion = document.getSourceVersion();
        if (isPreClassifiedUnsupported(document.getMimeType())) {
            String reasonCode = "mime type is not eligible for indexing";
            boolean applied = finalizeNonSuccess(documentId, eligibility, capturedSourceVersion, SKIPPED_UNSUPPORTED,
                    reasonCode);
            return applied ? new IndexProcessingResult(IndexProcessingOutcome.SKIPPED_UNSUPPORTED, reasonCode)
                    : ineligible(IneligibilityStage.FINAL_GENERATION_FENCE);
        }
        SourceType sourceType;
        try {
            sourceType = SourceType.valueOf(eligibility.connection().getType());
        } catch (IllegalArgumentException e) {
            return ineligibleSourceAccess("UNRECOGNIZED_SOURCE_TYPE");
        }
        DocumentSourceConnector connector = sourceConnectorRegistry.getConnector(sourceType).orElse(null);
        if (connector == null) {
            return ineligibleSourceAccess("CONNECTOR_UNAVAILABLE");
        }

        UserContext ownerContext = new UserContext(eligibility.connection().getOwnerSubject(), null, Set.of(),
                Set.of());
        SourceContentResult content;
        try {
            content = connector.fetchContent(ownerContext, document.getSourceId(), document.getSourceDocumentId(),
                    document.getSourceVersion());
        } catch (RuntimeException fetchFailure) {
            throw new TransientIndexingException(
                    "source content fetch failed: " + fetchFailure.getClass().getSimpleName());
        }
        if (content.outcome() != SourceContentOutcome.VERIFIED) {
            return handleUnverifiedContent(eligibility, content.outcome());
        }

        byte[] bytes = content.content();
        try {
            IndexOutcome indexOutcome = documentParsingClient.index(bytes, document.getName(), content.mimeType());
            return switch (indexOutcome.kind()) {
                case SUCCESS -> publishGenerationAfterRecheck(documentId, eligibility, connector, ownerContext,
                        content.verifiedSourceVersion(), indexOutcome);
                case UNSUPPORTED_FORMAT -> {
                    String reasonCode = reasonCodeFor(indexOutcome.kind());
                    boolean applied = finalizeNonSuccess(documentId, eligibility, content.verifiedSourceVersion(),
                            SKIPPED_UNSUPPORTED, reasonCode);
                    yield applied ? new IndexProcessingResult(IndexProcessingOutcome.SKIPPED_UNSUPPORTED, reasonCode)
                            : ineligible(IneligibilityStage.FINAL_GENERATION_FENCE);
                }
                case NO_TEXT -> {
                    String reasonCode = reasonCodeFor(indexOutcome.kind());
                    boolean applied = finalizeNonSuccess(documentId, eligibility, content.verifiedSourceVersion(),
                            SKIPPED_NO_TEXT, reasonCode);
                    yield applied ? new IndexProcessingResult(IndexProcessingOutcome.SKIPPED_NO_TEXT, reasonCode)
                            : ineligible(IneligibilityStage.FINAL_GENERATION_FENCE);
                }
                // 원본 AI 서비스 사유 문자열은 절대 옮기지 않는다(임의 텍스트 - Content 안전
                // 규칙 위반 가능성) - 고정 허용 사유 코드만 예외 메시지에 담는다.
                case TIMEOUT, FAILED -> throw new TransientIndexingException(
                        "ai service reported failure: " + reasonCodeFor(indexOutcome.kind()));
            };
        } finally {
            // 원본 Byte는 여기서 소비가 끝난다 - 참조를 남기지 않는다(Best-effort - JVM이 물리적
            // 메모리 소거를 보장하지 않는다는 한계는 CLAUDE.md/SharedFileDownloadService와 동일).
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * M17 진단 세분화(2차 교정, 이 작업 지시사항 1번) - 이전에는 여기 도달하는 11개
     * {@link SourceContentOutcome} 값 전부가 {@code INELIGIBLE_AT_SOURCE_ACCESS} 하나로
     * 뭉개졌다(Version 문제/삭제·휴지통/Export 상한/Credential 문제/권한 거부/판단 불가를
     * 구분할 수 없었다). 이제 각 값을 {@link #ineligibleSourceAccess}로 1:1 매핑한다 -
     * 새 값도, 임의 문자열도 아니고, 이미 이 Enum에 정의된 상수 이름 그대로만 고정
     * 접미사로 붙인다(명시적 허용 목록 - Google 응답/토큰/파일명/버전 원문은 여전히
     * 전혀 담기지 않는다). {@code UNSUPPORTED_FORMAT}은 여전히 별도 outcome({@code
     * SKIPPED_UNSUPPORTED})이라 이 매핑 대상이 아니다. {@code default} 분기(현재
     * 도달 가능한 값: {@code NOT_DOWNLOADABLE}/{@code TIMEOUT}/{@code FAILED})는
     * 기존 재시도 분류를 그대로 보존한다 - 이번 교정 대상이 아니다.
     */
    private IndexProcessingResult handleUnverifiedContent(Eligibility eligibility, SourceContentOutcome outcome) {
        Long documentId = eligibility.document().getId();
        String capturedSourceVersion = eligibility.document().getSourceVersion();
        return switch (outcome) {
            case UNSUPPORTED_FORMAT -> {
                String reasonCode = "source format is not indexable";
                boolean applied = finalizeNonSuccess(documentId, eligibility, capturedSourceVersion,
                        SKIPPED_UNSUPPORTED, reasonCode);
                yield applied ? new IndexProcessingResult(IndexProcessingOutcome.SKIPPED_UNSUPPORTED, reasonCode)
                        : ineligible(IneligibilityStage.FINAL_GENERATION_FENCE);
            }
            // 삭제/휴지통/Version 경합/Export 상한 - 다음 관련 Catalog Sync/Share 이벤트가 다시 판단한다.
            case NOT_FOUND -> ineligibleSourceAccess("NOT_FOUND");
            case TRASHED -> ineligibleSourceAccess("TRASHED");
            case DOCUMENT_CHANGED -> ineligibleSourceAccess("DOCUMENT_CHANGED");
            case VERSION_MISMATCH -> ineligibleSourceAccess("VERSION_MISMATCH");
            case EXPORT_LIMIT_EXCEEDED -> ineligibleSourceAccess("EXPORT_LIMIT_EXCEEDED");
            // Credential/권한 문제 - 재연결/재공유 같은 새 이벤트가 있어야 다시 시도할 이유가 생긴다.
            // 지금 당장 재시도해도 결과가 달라질 근거가 없으므로 Bounded Retry 대상으로 삼지 않는다.
            case MISSING_CREDENTIAL -> ineligibleSourceAccess("MISSING_CREDENTIAL");
            case CREDENTIAL_NOT_BOUND_TO_USER -> ineligibleSourceAccess("CREDENTIAL_NOT_BOUND_TO_USER");
            case CREDENTIAL_UNREADABLE -> ineligibleSourceAccess("CREDENTIAL_UNREADABLE");
            case INSUFFICIENT_SCOPE -> ineligibleSourceAccess("INSUFFICIENT_SCOPE");
            case ACCESS_DENIED -> ineligibleSourceAccess("ACCESS_DENIED");
            case ACCESS_UNKNOWN -> ineligibleSourceAccess("ACCESS_UNKNOWN");
            default -> throw new TransientIndexingException("source content fetch failed: " + outcome.name());
        };
    }

    /** {@code SKIPPED_INELIGIBLE} + 고정 단계 사유 코드 쌍을 만드는 최소 도우미. */
    private static IndexProcessingResult ineligible(IneligibilityStage stage) {
        return new IndexProcessingResult(IndexProcessingOutcome.SKIPPED_INELIGIBLE, stage.reasonCode());
    }

    /**
     * M17 진단 세분화(2차) - {@code SOURCE_ACCESS} 단계 안에서, 실제로 어느 typed
     * 원인이었는지 고정 접미사로 구분한다. {@code detail}은 항상 호출부의 문자열
     * 리터럴(이미 {@link SourceContentOutcome} 상수 이름 또는 이 Class 안에서 정의한
     * Connector 해석 실패 두 가지 고정값 중 하나)이라 결과 문자열 집합은 유한하고
     * 코드 리뷰로 전부 열거 가능하다 - 호출자 입력이나 런타임 값에서 파생되지 않는다.
     */
    private static IndexProcessingResult ineligibleSourceAccess(String detail) {
        return new IndexProcessingResult(IndexProcessingOutcome.SKIPPED_INELIGIBLE,
                IneligibilityStage.SOURCE_ACCESS.reasonCode() + "_" + detail);
    }

    /**
     * M11 후속 교정(이 작업 지시사항 2번, "recheck current provider access/version
     * before publication, outside DB locks") - AI 파싱/임베딩이 끝난 뒤, 아직 어떤
     * DB Transaction/Lock도 열지 않은 상태에서 {@link
     * DocumentSourceConnector#verifyCurrentMetadata}로 요청자 본인 결합 기준 현재
     * 접근권한/Version을 한 번 더 확인한다. 이 확인이 실패하거나(Network 등) 지금
     * 이 순간의 Version이 방금 Fetch/Parse에 썼던 {@code fetchedSourceVersion}과
     * 다르면, 그 사이(Parsing에 걸린 시간 동안) Provider 쪽 상태가 바뀐 것이므로
     * 이 발행을 시도하지 않는다 - 다음 관련 Catalog Sync/Share 이벤트가 새로
     * 판단하게 한다. Google/Python 호출 자체는 이 지점 전체가 어떤 DB Lock도 쥐지
     * 않은 채로 이뤄진다(Class Javadoc 원칙 유지).
     */
    private IndexProcessingResult publishGenerationAfterRecheck(Long documentId, Eligibility eligibility,
            DocumentSourceConnector connector, UserContext ownerContext, String fetchedSourceVersion,
            IndexOutcome indexOutcome) {
        SourceDocumentEntity document = eligibility.document();
        SourceMetadataVerificationResult recheck;
        try {
            recheck = connector.verifyCurrentMetadata(ownerContext, document.getSourceId(),
                    document.getSourceDocumentId());
        } catch (RuntimeException recheckFailure) {
            throw new TransientIndexingException(
                    "post-parse metadata recheck failed: " + recheckFailure.getClass().getSimpleName());
        }
        if (recheck.outcome() != SourceMetadataVerificationOutcome.VERIFIED
                || !fetchedSourceVersion.equals(recheck.sourceVersion())) {
            // Provider 접근/Version이 Parsing 도중 바뀌었다 - 이 오래된 결과를 발행하지
            // 않는다("provider version/access changes during embedding before catalog
            // sync catches up").
            return ineligible(IneligibilityStage.VERSION_RECHECK);
        }
        return publishGeneration(documentId, eligibility, fetchedSourceVersion, indexOutcome);
    }

    /**
     * 발행 직전 재검증 + 원자적 Generation 교체를 하나의 짧은 새 Transaction 안에서
     * 수행한다({@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} - 호출자가 이미
     * 진행 중인 Transaction을 갖고 있지 않다는 전제와 무관하게 항상 새로 연다). Google/
     * Python 호출이 전부 끝난 뒤에만 호출되므로, 이 Transaction 동안 어떤 외부 I/O도
     * 일어나지 않는다.
     *
     * <h2>M11 후속 교정(이 작업 지시사항 2번) - ACTIVE 상태만으로는 부족했다</h2>
     * <p>이전에는 연결 {@code status=ACTIVE}와 문서 {@code source_version} 일치만
     * 재확인했다 - 그러나 "ACTIVE -> Disconnect -> 재연결로 다시 ACTIVE"는 이 두
     * 조건 모두 통과할 수 있다(재연결이 {@code status}를 그대로 ACTIVE로 되돌리기
     * 때문이다). 이제 {@code eligibility}가 자격 판단 시점에 캡처해 둔 불변
     * 스냅샷(연결 {@code connectionEpoch}, 공유 {@code id}+{@code generation})을
     * 이 잠긴 최신 상태와 정확히 비교한다 - Disconnect가 항상 {@code
     * connectionEpoch}를 올리므로, 그 사이 어떤 Disconnect(뒤이은 재연결 여부와
     * 무관하게)가 있었다면 이 오래된 연산은 실패한다. 공유도 이제 "지금 활성인
     * 아무 공유"가 아니라 정확히 캡처해 둔 그 {@code shareId}를 {@code
     * documentShareJpaRepository.findByIdForUpdate}로 잠가 재확인한다 - unshare 후
     * 새로 만들어진 다른 공유가 이 낡은 연산을 우연히 되살리지 못하게 하고, 이
     * 명시적 Lock이 {@code updateShare}/{@code adminSetBlocked}의 동시 쓰기와
     * 실제로 직렬화되게 한다("checking a mutable share without protecting against
     * concurrent changes is insufficient"). Lock 순서는 항상 {@code
     * source_connections} → {@code source_documents} → {@code document_shares}다.</p>
     */
    private IndexProcessingResult publishGeneration(Long documentId, Eligibility eligibility,
            String verifiedSourceVersion, IndexOutcome indexOutcome) {
        if (!isWellFormed(indexOutcome)) {
            throw new TransientIndexingException("malformed index response from ai service");
        }
        Instant now = clock.instant();
        List<DocumentEmbeddingEntity> rows = new ArrayList<>(indexOutcome.chunks().size());
        for (EmbeddingChunk chunk : indexOutcome.chunks()) {
            rows.add(new DocumentEmbeddingEntity(documentId, chunk.chunkIndex(), chunk.locatorType().name(),
                    chunk.locatorValue(), toVectorLiteral(chunk.embedding()), verifiedSourceVersion,
                    chunk.contentHmac(), indexOutcome.parserVersion(), indexOutcome.chunkingVersion(),
                    EXPECTED_EMBEDDING_MODEL, now));
        }

        Boolean published = publishTransaction.execute(status -> {
            LockedIdentity locked = lockAndRevalidate(documentId, eligibility, verifiedSourceVersion);
            if (locked == null) {
                return false;
            }
            SecurityLevel classification;
            try {
                classification = SecurityLevel.valueOf(locked.share().getClassification());
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (aiUsagePolicyService.evaluate(classification, AiRequestContext.local()).isDenied()) {
                return false;
            }
            documentEmbeddingJpaRepository.replaceGeneration(documentId, rows);
            sourceDocumentJpaRepository.updateIndexStatus(documentId, INDEXED, null);
            return true;
        });

        return Boolean.TRUE.equals(published) ? new IndexProcessingResult(IndexProcessingOutcome.INDEXED, null)
                : ineligible(IneligibilityStage.FINAL_GENERATION_FENCE);
    }

    /**
     * M11 후속 교정(2026-09-16, 이번 작업 지시사항 A) - "Make non-success state writes
     * transactional and generation-safe." {@link #isPreClassifiedUnsupported}/{@link
     * #handleUnverifiedContent}/AI 서비스 UNSUPPORTED_FORMAT·NO_TEXT 결과 전부가 이제
     * {@link #publishGeneration}과 정확히 같은 짧은 새 Transaction({@link
     * TransactionDefinition#PROPAGATION_REQUIRES_NEW})과 같은 Lock 순서/펜싱({@link
     * #lockAndRevalidate})을 거친다 - Google/Python 호출이 전부 끝난 뒤(또는 애초에
     * 호출되지 않은 뒤)에만 호출되므로, 이 Transaction 동안 어떤 외부 I/O도 일어나지
     * 않는다.
     *
     * <h2>왜 이전 코드로는 부족했는가</h2>
     * <p>이전에는 이 결과들을 {@code sourceDocumentJpaRepository.updateIndexStatusIfCurrent}
     * 단일 호출로 직접 기록했다 - {@code source_version} 일치 + "아직 INDEXED가 아님"만
     * 확인하는 한 문장짜리 조건부 UPDATE였다. 이것만으로는 "정확히 이 연결/공유
     * 세대"인지 확인하지 못한다("indexStatus != INDEXED" alone is not a generation
     * fence") - {@link #publishGeneration}이 이미 갖춘 연결 {@code connectionEpoch}/
     * 공유 {@code shareId}+{@code generation} 재검증이 성공(발행) 경로에만 있고 비성공
     * 경로에는 전혀 없었다. 이제 두 경로가 정확히 같은 {@link #lockAndRevalidate}를
     * 공유해, 재연결/공유 변경 경합이 비성공 기록도 똑같이 펜싱한다.</p>
     *
     * <p>펜싱을 통과해도 이미 {@code INDEXED}인 문서는 절대 덮어쓰지 않는다(더
     * 새로운 성공을 낡은 실패/미지원 판정으로 되돌리지 않는다) - {@link
     * SourceDocumentJpaRepository#updateIndexStatusIfCurrent}를 Lock 보유 상태에서
     * 그대로 재사용한다(Java 레벨 검증에 더해 SQL {@code WHERE} 절 자체의 이중 방어,
     * 비용은 없다).</p>
     *
     * @return 실제로 기록이 적용됐으면 {@code true} - 펜싱으로 조용히 무시됐으면
     *         {@code false}(호출자가 이 경우 {@link IndexProcessingOutcome#SKIPPED_INELIGIBLE}로
     *         대체한다 - {@link #publishGeneration}이 펜싱 실패 시 하는 것과 동일).
     */
    private boolean finalizeNonSuccess(Long documentId, Eligibility eligibility, String expectedSourceVersion,
            String status, String reasonCode) {
        Boolean applied = publishTransaction.execute(txStatus -> {
            LockedIdentity locked = lockAndRevalidate(documentId, eligibility, expectedSourceVersion);
            if (locked == null) {
                return false;
            }
            if (INDEXED.equals(locked.document().getIndexStatus())) {
                // 이미 더 새로운 시도가 성공적으로 색인을 마쳤다 - 이 오래된 비성공 판정이
                // 그 성공을 덮어쓰지 않는다.
                return false;
            }
            int rows = sourceDocumentJpaRepository.updateIndexStatusIfCurrent(documentId, status, reasonCode,
                    expectedSourceVersion);
            return rows > 0;
        });
        return Boolean.TRUE.equals(applied);
    }

    /**
     * {@link #publishGeneration}과 {@link #finalizeNonSuccess}가 공유하는 발행 직전
     * Lock/재검증 체인 - 호출자가 이미 {@link #publishTransaction} 안에 있다고
     * 가정한다(스스로 Transaction을 열지 않는다). 부모({@code source_connections}) ->
     * 자식({@code source_documents}) -> {@code document_shares} 순서로 잠근다(Class
     * Javadoc 참고). 통과하면 세 Locked Entity를, 하나라도 자격 판단 시점의 캡처값과
     * 다르면 {@code null}을 반환한다 - 어느 경우든 호출자가 값을 재사용할 수 있도록
     * 실제로 잠근 최신 Entity를 그대로 돌려준다(다시 조회하지 않는다).
     */
    private LockedIdentity lockAndRevalidate(Long documentId, Eligibility eligibility, String expectedSourceVersion) {
        Long sourceId = eligibility.document().getSourceId();
        long capturedConnectionEpoch = eligibility.connection().getConnectionEpoch();
        Long capturedShareId = eligibility.share().getId();
        long capturedShareGeneration = eligibility.share().getGeneration();

        SourceConnectionEntity lockedConnection = sourceConnectionJpaRepository.findByIdForUpdate(sourceId)
                .orElse(null);
        if (lockedConnection == null || !GOOGLE_DRIVE_TYPE.equals(lockedConnection.getType())
                || !ACTIVE.equals(lockedConnection.getStatus())
                || lockedConnection.getConnectionEpoch() != capturedConnectionEpoch) {
            // 연결이 비활성이거나, Epoch가 캡처 시점과 다르다 - 그 사이 Disconnect(뒤이은
            // 재연결 포함)가 있었다는 뜻이다. "A new... reconnect... must not make the
            // OLD operation valid again."
            return null;
        }
        SourceDocumentEntity lockedDocument = sourceDocumentJpaRepository.findByIdForUpdate(documentId).orElse(null);
        if (lockedDocument == null || !ACTIVE.equals(lockedDocument.getState())
                || !expectedSourceVersion.equals(lockedDocument.getSourceVersion())) {
            // 다른 Sync가 이미 새 Version을 반영했다 - 이 낡은 결과를 기록하면 "Old work
            // overwriting/deleting a newer generation"이 된다. 조용히 포기한다.
            return null;
        }
        DocumentShareEntity lockedShare = documentShareJpaRepository.findByIdForUpdate(capturedShareId).orElse(null);
        if (lockedShare == null || !lockedShare.isActive() || lockedShare.isAdminBlocked()
                || lockedShare.getGeneration() != capturedShareGeneration) {
            // 정확히 이 공유가 철회/차단됐거나 그 사이 수정(등급/행위 변경)됐다 - 새 공유나
            // 새 Generation이 우연히 유효하더라도 이 낡은 연산을 되살리지 않는다.
            return null;
        }
        return new LockedIdentity(lockedConnection, lockedDocument, lockedShare);
    }

    /** 발행 전 마지막 방어선 - Python/Client 계층 검증을 신뢰하지 않고 DB에 쓰기 직전 다시 확인한다. */
    private static boolean isWellFormed(IndexOutcome outcome) {
        if (!EXPECTED_EMBEDDING_MODEL.equals(outcome.embeddingModel())) {
            return false;
        }
        if (outcome.chunks().isEmpty()) {
            return false;
        }
        Set<Integer> seenChunkIndexes = new HashSet<>();
        for (EmbeddingChunk chunk : outcome.chunks()) {
            if (chunk.chunkIndex() < 0 || !seenChunkIndexes.add(chunk.chunkIndex())) {
                return false;
            }
            if (chunk.embedding().size() != DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS) {
                return false;
            }
            for (Float value : chunk.embedding()) {
                if (value == null || !Float.isFinite(value)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String toVectorLiteral(List<Float> embedding) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding.get(i));
        }
        return builder.append(']').toString();
    }

    private Eligibility resolveEligibility(Long documentId) {
        SourceDocumentEntity document = sourceDocumentJpaRepository.findById(documentId).orElse(null);
        if (document == null || !ACTIVE.equals(document.getState())) {
            return null;
        }
        DocumentShareEntity share = documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(documentId)
                .orElse(null);
        if (share == null || share.isAdminBlocked() || !hasConsistentAudience(share)) {
            return null;
        }
        SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(document.getSourceId())
                .orElse(null);
        if (connection == null || !GOOGLE_DRIVE_TYPE.equals(connection.getType())
                || !ACTIVE.equals(connection.getStatus())) {
            return null;
        }
        SecurityLevel classification;
        try {
            classification = SecurityLevel.valueOf(share.getClassification());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (aiUsagePolicyService.evaluate(classification, AiRequestContext.local()).isDenied()) {
            return null;
        }
        return new Eligibility(document, connection, share);
    }

    private boolean hasConsistentAudience(DocumentShareEntity share) {
        try {
            long recipientCount = documentShareJpaRepository.countRecipients(share.getId());
            return switch (ShareAudience.valueOf(share.getAudience())) {
                case ALL_AUTHENTICATED -> recipientCount == 0;
                case NAMED_USERS -> recipientCount > 0;
            };
        } catch (IllegalArgumentException malformedAudience) {
            return false;
        }
    }

    private static boolean isPreClassifiedUnsupported(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            // 알 수 없음 - 미리 넘겨짚지 않는다. Connector의 권위 있는 판단에 맡긴다.
            return false;
        }
        String normalized = mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        for (String prefix : KNOWN_UNSUPPORTED_MIME_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * M11 후속 교정(이 작업 지시사항 3번, "arbitrary AI-service reasons" 금지) - AI
     * 서비스가 보낸 {@code IndexOutcome.reason()}(임의 Free-text, Content 안전
     * 규칙이 검증하지 않은 값)을 절대 그대로 DB/예외 메시지로 옮기지 않는다. 이
     * 분류({@code kind()})만으로 결정되는 작고 고정된 허용 사유 코드 집합으로
     * 대체한다.
     */
    private static String reasonCodeFor(ParseOutcomeKind kind) {
        return switch (kind) {
            case UNSUPPORTED_FORMAT -> "AI_SERVICE_UNSUPPORTED_FORMAT";
            case NO_TEXT -> "AI_SERVICE_NO_TEXT";
            case TIMEOUT, FAILED -> "AI_SERVICE_PROCESSING_FAILED";
            case SUCCESS -> throw new IllegalStateException("reasonCodeFor must not be called for SUCCESS");
        };
    }

    /** 자격 판단 시점에 캡처한 불변 스냅샷 - {@link #publishGeneration}/{@link #finalizeNonSuccess} 발행 직전 펜싱에 사용한다. */
    private record Eligibility(SourceDocumentEntity document, SourceConnectionEntity connection,
            DocumentShareEntity share) {
    }

    /** {@link #lockAndRevalidate}가 발행 직전 실제로 잠근 최신 Entity 3종. */
    private record LockedIdentity(SourceConnectionEntity connection, SourceDocumentEntity document,
            DocumentShareEntity share) {
    }
}
