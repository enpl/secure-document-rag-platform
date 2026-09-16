package com.sdv.rag.application;

import com.sdv.common.model.UserContext;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.policy.domain.AiRequestContext;
import com.sdv.source.application.SourceConnectorRegistry;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.ShareAction;
import com.sdv.source.domain.SourceAccessContext;
import com.sdv.source.domain.SourceMetadataVerificationOutcome;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F-BE-207(M12 신규, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.5 Mandatory Live
 * Retrieval). {@link com.sdv.source.application.SharedFileDownloadService}(SHR-004)가
 * 이미 확립한 "Fetch 전/후 신선한(Fresh) 인가 Snapshot 재확인" 접근법을 그대로
 * 재사용한다 - Provider I/O는 어떤 DB Transaction/Lock 밖에서도 수행된다. 이
 * Class 자체는 SDV 인가 다운로드({@code ShareAction.DOWNLOAD})와 아무 관련이 없다
 * - AI Live Retrieval은 항상 {@code ShareAction.VIEW} 부여 + {@link
 * EffectivePermissionService#evaluateSharedAccess}의 AI Usage Policy 단계로만
 * 판단한다({@link DocumentSourceConnector#verifyForAi}/{@link
 * DocumentSourceConnector#fetchForAi} 참고).
 *
 * <p>{@link #verifyBefore}가 캡처하는 {@link LiveIdentity}는 이후 외부 I/O(Google
 * Fetch, Python Parse) 도중 절대 DB에서 다시 읽지 않는 불변 스냅샷이다. {@link
 * #verifyAfter}는 그 스냅샷과 지금 이 순간의 상태(공유/연결 세대, Provider
 * 접근/버전)를 비교한다 - 하나라도 다르면 이번 시도 전체를 무효로 만든다(Revoke,
 * 관리자 차단, Disconnect/재연결, 재공유가 모두 여기서 걸린다).</p>
 */
@Service
public class SourceConsistencyGuard {

    private final DocumentShareJpaRepository documentShareJpaRepository;
    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final EffectivePermissionService effectivePermissionService;
    private final SourceConnectorRegistry sourceConnectorRegistry;
    private final TransactionTemplate freshRead;

    public SourceConsistencyGuard(DocumentShareJpaRepository documentShareJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            EffectivePermissionService effectivePermissionService, SourceConnectorRegistry sourceConnectorRegistry,
            PlatformTransactionManager transactionManager) {
        this.documentShareJpaRepository = documentShareJpaRepository;
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.effectivePermissionService = effectivePermissionService;
        this.sourceConnectorRegistry = sourceConnectorRegistry;
        this.freshRead = new TransactionTemplate(transactionManager);
        this.freshRead.setReadOnly(true);
        this.freshRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 외부 I/O 전 신선한 인가 Snapshot을 만들고, 요청자 SDV 공유 + AI Usage Policy +
     * 게시자 Provider 접근/버전을 모두 확인한다. 어느 하나라도 실패하면 {@link
     * LiveRetrievalException}(Content-Free 사유)을 던진다 - 그 이상 아무 Byte도
     * Fetch하지 않는다.
     */
    public LiveIdentity verifyBefore(UserContext requester, Long documentId, long deadlineMs) {
        Snapshot snapshot = readFreshSnapshot(requester.subject(), documentId);
        authorize(requester, snapshot.context());
        DocumentSourceConnector connector = sourceConnectorRegistry.getConnector(snapshot.sourceType())
                .orElseThrow(() -> new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AVAILABLE));
        SourceMetadataVerificationResult live = connector.verifyForAi(snapshot.context(), deadlineMs);
        if (live.outcome() != SourceMetadataVerificationOutcome.VERIFIED) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AVAILABLE);
        }
        return new LiveIdentity(requester, snapshot, connector, live.sourceVersion(), live.mimeType(), live.name());
    }

    /**
     * Fetch/Parse 직후, 근거를 실제로 노출하기 전의 마지막 재확인. Provider 재확인을
     * 먼저 수행한 뒤(버전 변경은 {@link LiveRetrievalException.Reason#DOCUMENT_CHANGED}로
     * 구분해 호출자가 1회 재시도를 판단할 수 있게 한다), SDV 측을 완전히 새로 읽은
     * Snapshot으로 재확인한다 - {@code before}가 들고 있던 Cached Entity를 절대 다시
     * 쓰지 않는다.
     */
    public void verifyAfter(LiveIdentity before, long deadlineMs) {
        SourceMetadataVerificationResult live = before.connector().verifyForAi(before.snapshot().context(),
                deadlineMs);
        if (live.outcome() != SourceMetadataVerificationOutcome.VERIFIED) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AVAILABLE);
        }
        if (!before.expectedSourceVersion().equals(live.sourceVersion())) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.DOCUMENT_CHANGED);
        }
        Snapshot current = readFreshSnapshot(before.requester().subject(), before.snapshot().context().documentId());
        if (!sameBinding(before.snapshot(), current)) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AUTHORIZED);
        }
        authorize(before.requester(), current.context());
    }

    private Snapshot readFreshSnapshot(String requesterSubject, Long documentId) {
        Snapshot snapshot = freshRead.execute(status -> {
            DocumentShareEntity share = documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(documentId)
                    .orElse(null);
            if (share == null) {
                return null;
            }
            SourceDocumentEntity document = sourceDocumentJpaRepository
                    .findByIdAndOwnerSubject(share.getDocumentId(), share.getPublisherSubject()).orElse(null);
            SourceConnectionEntity connection = sourceConnectionJpaRepository
                    .findByIdAndOwnerSubject(share.getSourceId(), share.getPublisherSubject()).orElse(null);
            if (document == null || connection == null || !share.getSourceId().equals(document.getSourceId())) {
                return null;
            }
            try {
                SourceAccessContext context = new SourceAccessContext(requesterSubject, share.getPublisherSubject(),
                        share.getSourceId(), share.getDocumentId(), share.getId(), ShareAction.VIEW,
                        share.getGeneration(), connection.getConnectionEpoch());
                return new Snapshot(context, SourceType.valueOf(connection.getType()));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        });
        if (snapshot == null) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AUTHORIZED);
        }
        return snapshot;
    }

    private void authorize(UserContext requester, SourceAccessContext context) {
        // AI Usage Policy는 Local Embedding/Local LLM 전제다(§2A.5, External은 기본 OFF) -
        // 여기서 External Provider를 요청하지 않는다.
        if (effectivePermissionService.evaluateSharedAccess(requester, context, AiRequestContext.local()).isDenied()) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AUTHORIZED);
        }
    }

    private static boolean sameBinding(Snapshot before, Snapshot after) {
        return before.context().equals(after.context()) && before.sourceType() == after.sourceType();
    }

    record Snapshot(SourceAccessContext context, SourceType sourceType) {
    }

    /**
     * 외부 I/O 시작 전에 캡처한 불변 신원 - {@link #verifyAfter}가 이 값과 지금 이
     * 순간의 상태를 비교한다. {@code expectedSourceVersion}/{@code mimeType}/{@code
     * name}은 {@link #verifyBefore} 시점에 Provider가 실제로 보고한 값이다(요청자가
     * 제공한 값이 아니다).
     */
    public record LiveIdentity(UserContext requester, Snapshot snapshot, DocumentSourceConnector connector,
            String expectedSourceVersion, String mimeType, String name) {

        public SourceAccessContext context() {
            return snapshot.context();
        }
    }
}
