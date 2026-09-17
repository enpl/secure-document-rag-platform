package com.sdv.rag.application;

import com.sdv.common.model.UserContext;
import com.sdv.audit.application.RagAuditRecorder;
import com.sdv.rag.application.port.out.EphemeralEvidenceCapacityExceededException;
import com.sdv.rag.application.port.out.EphemeralEvidenceInvalidatedException;
import com.sdv.rag.application.port.out.EphemeralEvidenceStore;
import com.sdv.rag.domain.EvidenceHandle;
import com.sdv.rag.domain.EvidenceKey;
import com.sdv.rag.domain.ExtractedLocation;
import com.sdv.rag.domain.LiveRetrievalResult;
import com.sdv.rag.domain.LiveRetrievalStatus;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import com.sdv.rag.domain.VectorCandidate;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * F-BE-103(M12 신규, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.5 Mandatory Live
 * Retrieval). 파일 하나에 대한 전체 흐름을 조립한다: {@link SourceConsistencyGuard#verifyBefore}
 * -> {@link com.sdv.source.application.port.DocumentSourceConnector#fetchForAi}(Bounded
 * Transient Fetch) -> {@link DocumentParsingClient#parse}(Isolated Parse) ->
 * 최소 근거 선택 -> {@link SourceConsistencyGuard#verifyAfter} -> {@link
 * EphemeralEvidenceStore#putEncrypted}(암호화된 Ephemeral 저장) 순서다. 평문
 * 원본/추출 Text는 이 메서드 호출 하나의 지역 변수 범위 밖으로 나가지 않는다 -
 * 저장되는 것은 {@link #selectEvidence}가 고른, 상한 안의 발췌뿐이다.
 *
 * <h2>버전 변경 - 시도 전체 폐기 후 최대 1회 재시도</h2>
 * <p>{@link #retrieveLive}는 {@link #attempt}가 {@code DOCUMENT_CHANGED}로
 * 실패하면(Fetch 전/후 어느 쪽이든) {@link #retryOnVersionChange}를 정확히 한 번
 * 더 호출한다 - 이전 시도의 어떤 중간 결과(Fetch한 Byte, Parse 결과)도 재사용하지
 * 않고 {@link SourceConsistencyGuard#verifyBefore}부터 처음부터 다시 시작한다.
 * 그 재시도마저 또 {@code DOCUMENT_CHANGED}면 최종적으로 {@link
 * LiveRetrievalStatus#DOCUMENT_CHANGED}를 반환한다("Never relabel stale
 * text/locators as a new version" - 이 작업 지시사항).</p>
 */
@Service
public class LiveEvidenceRetrievalService {

    private final SourceConsistencyGuard sourceConsistencyGuard;
    private final DocumentParsingClient documentParsingClient;
    private final EphemeralEvidenceStore ephemeralEvidenceStore;
    private final LiveRetrievalProperties properties;
    private final EvidenceConversationLifecycle conversationLifecycle;
    private final LiveContentAdmission contentAdmission;
    private final RagAuditRecorder audit;

    public LiveEvidenceRetrievalService(SourceConsistencyGuard sourceConsistencyGuard,
            DocumentParsingClient documentParsingClient, EphemeralEvidenceStore ephemeralEvidenceStore,
            LiveRetrievalProperties properties, EvidenceConversationLifecycle conversationLifecycle) {
        this(sourceConsistencyGuard, documentParsingClient, ephemeralEvidenceStore, properties, conversationLifecycle,
                new LiveContentAdmission(2, 50L * 1024 * 1024), RagAuditRecorder.noop());
    }

    public LiveEvidenceRetrievalService(SourceConsistencyGuard sourceConsistencyGuard,
            DocumentParsingClient documentParsingClient, EphemeralEvidenceStore ephemeralEvidenceStore,
            LiveRetrievalProperties properties, EvidenceConversationLifecycle conversationLifecycle,
            LiveContentAdmission contentAdmission) {
        this(sourceConsistencyGuard, documentParsingClient, ephemeralEvidenceStore, properties, conversationLifecycle,
                contentAdmission, RagAuditRecorder.noop());
    }

    @Autowired
    public LiveEvidenceRetrievalService(SourceConsistencyGuard sourceConsistencyGuard,
            DocumentParsingClient documentParsingClient, EphemeralEvidenceStore ephemeralEvidenceStore,
            LiveRetrievalProperties properties, EvidenceConversationLifecycle conversationLifecycle,
            LiveContentAdmission contentAdmission, RagAuditRecorder audit) {
        this.sourceConsistencyGuard = sourceConsistencyGuard;
        this.documentParsingClient = documentParsingClient;
        this.ephemeralEvidenceStore = ephemeralEvidenceStore;
        this.properties = properties;
        this.conversationLifecycle = conversationLifecycle;
        this.contentAdmission = contentAdmission;
        this.audit = audit;
    }

    /**
     * {@code candidate}는 Vector 검색이 준 힌트(있으면 그 Locator를 새로 Parse한
     * 콘텐츠에서 다시 찾는다)이거나, 직접 선택된 파일이면 {@code null}이다(이 경우
     * 첫 번째 위치를 대표 발췌로 쓰고 {@code partialCoverage}로 정직하게 알린다).
     */
    public LiveRetrievalResult retrieveLive(UserContext requester, Long documentId, String conversationId,
            VectorCandidate candidate) {
        return retrieveLive(requester, documentId, conversationId, candidate,
                LiveRetrievalDeadline.startingNow(properties.perFileDeadlineMs()));
    }

    LiveRetrievalResult retrieveLive(UserContext requester, Long documentId, String conversationId,
            VectorCandidate candidate, LiveRetrievalDeadline deadline) {
        EvidenceConversationLifecycle.Lease lease = null;
        try {
            lease = conversationLifecycle.requireActive(requester, conversationId);
            return attempt(requester, documentId, conversationId, candidate, true, lease, deadline);
        } catch (LiveRetrievalException e) {
            if (e.reason() == LiveRetrievalException.Reason.DOCUMENT_CHANGED) {
                audit.documentStage(requester, "VERSION_DISCARD", documentId, "FAILURE", "DOCUMENT_CHANGED",
                        java.util.Map.of("retry", true));
                return retryOnVersionChange(requester, documentId, conversationId, candidate, lease, deadline);
            }
            audit.documentStage(requester, "LIVE_FAILURE", documentId, "FAILURE", e.reason().name(), java.util.Map.of());
            return LiveRetrievalResult.failed(documentId, mapStatus(e.reason()));
        }
    }

    /** 정확히 한 번, 이전 시도의 어떤 중간 결과도 재사용하지 않고 처음부터 다시 시도한다. */
    public LiveRetrievalResult retryOnVersionChange(UserContext requester, Long documentId, String conversationId,
            VectorCandidate candidate) {
        return retryOnVersionChange(requester, documentId, conversationId, candidate,
                conversationLifecycle.requireActive(requester, conversationId),
                LiveRetrievalDeadline.startingNow(properties.perFileDeadlineMs()));
    }

    private LiveRetrievalResult retryOnVersionChange(UserContext requester, Long documentId, String conversationId,
            VectorCandidate candidate, EvidenceConversationLifecycle.Lease lease, LiveRetrievalDeadline deadline) {
        try {
            audit.documentStage(requester, "VERSION_RETRY", documentId, "STARTED", "DOCUMENT_CHANGED",
                    java.util.Map.of("retry", true));
            return attempt(requester, documentId, conversationId, candidate, false, lease, deadline);
        } catch (LiveRetrievalException e) {
            audit.documentStage(requester, "LIVE_FAILURE", documentId, "FAILURE", e.reason().name(),
                    java.util.Map.of("retry", true));
            return LiveRetrievalResult.failed(documentId, mapStatus(e.reason()));
        }
    }

    private LiveRetrievalResult attempt(UserContext requester, Long documentId, String conversationId,
            VectorCandidate candidate, boolean allowVersionChangeSignal, EvidenceConversationLifecycle.Lease lease,
            LiveRetrievalDeadline deadline) {
        if (deadline.expired()) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.REQUEST_TIMEOUT);
        }
        if (!conversationLifecycle.isActive(lease)) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AUTHORIZED);
        }
        long documentFence = ephemeralEvidenceStore.captureDocumentFence(documentId);
        long conversationFence = ephemeralEvidenceStore.captureConversationFence(requester.subject(), conversationId);
        long deadlineMs = deadline.fileBudgetMillis(properties.perFileDeadlineMs());
        if (deadlineMs <= 0) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.REQUEST_TIMEOUT);
        }
        SourceConsistencyGuard.LiveIdentity before = sourceConsistencyGuard.verifyBefore(requester, documentId,
                deadlineMs);
        audit.documentStage(requester, "LIVE_PRE_VERIFICATION", documentId, "SUCCESS", "OK", java.util.Map.of(
                "shareGeneration", before.context().shareGeneration(),
                "connectionGeneration", before.context().connectionGeneration(),
                "sourceVersion", before.expectedSourceVersion()));
        long sourceFence = ephemeralEvidenceStore.captureSourceFence(before.context().sourceId());

        ParseOutcome parsed;
        try {
            try (LiveContentAdmission.Reservation ignored = contentAdmission.acquire(25L * 1024 * 1024, deadline)) {
                SourceContentResult content = before.connector().fetchForAi(before.context(),
                        before.expectedSourceVersion(), deadline.fileBudgetMillis(properties.perFileDeadlineMs()));
                if (content.outcome() != SourceContentOutcome.VERIFIED) {
                    throw mapContentFailure(content.outcome());
                }
                byte[] fetchedBytes = content.content();
                try {
                    parsed = documentParsingClient.parse(fetchedBytes, before.name(), before.mimeType(),
                            deadline.fileBudgetMillis(properties.perFileDeadlineMs()));
                } finally {
                    Arrays.fill(fetchedBytes, (byte) 0);
                }
            }
        } catch (EphemeralEvidenceCapacityExceededException e) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.CAPACITY_EXHAUSTED);
        }
        if (deadline.expired() || parsed.kind() == ParseOutcomeKind.TIMEOUT) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.REQUEST_TIMEOUT);
        }
        if (parsed.kind() == ParseOutcomeKind.UNSUPPORTED_FORMAT) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.UNSUPPORTED_FORMAT);
        }
        if (parsed.kind() != ParseOutcomeKind.SUCCESS) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NO_EVIDENCE);
        }

        EvidenceSelection selection = selectEvidence(parsed, documentId, before.expectedSourceVersion(), candidate);
        if (selection == null) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NO_EVIDENCE);
        }

        // 근거를 노출하기 전 마지막 재확인 - Fetch/Parse가 진행되는 동안의 Revoke/Block/
        // Disconnect/Version 변경을 여기서 잡는다.
        sourceConsistencyGuard.verifyAfter(before, deadline.fileBudgetMillis(properties.perFileDeadlineMs()));
        audit.documentStage(requester, "LIVE_POST_VERIFICATION", documentId, "SUCCESS", "OK", java.util.Map.of(
                "shareGeneration", before.context().shareGeneration(),
                "connectionGeneration", before.context().connectionGeneration(),
                "sourceVersion", before.expectedSourceVersion()));
        if (!conversationLifecycle.isActive(lease)) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AUTHORIZED);
        }

        EvidenceKey key = new EvidenceKey(requester.subject(), conversationId, before.context().sourceId(),
                documentId, before.context().shareId(), before.context().shareGeneration(),
                before.context().connectionGeneration(), before.context().requesterAuthorizationRevision(),
                before.expectedSourceVersion());
        EvidenceHandle handle;
        try {
            handle = ephemeralEvidenceStore.putEncryptedFenced(key, selection.text().getBytes(StandardCharsets.UTF_8),
                    documentFence, sourceFence, conversationFence);
        } catch (EphemeralEvidenceCapacityExceededException e) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.CAPACITY_EXHAUSTED);
        } catch (EphemeralEvidenceInvalidatedException e) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AUTHORIZED);
        }
        try {
            audit.documentStage(requester, "EVIDENCE_ADMISSION", documentId, "SUCCESS", "OK", java.util.Map.of(
                    "shareGeneration", before.context().shareGeneration(),
                    "connectionGeneration", before.context().connectionGeneration(),
                    "sourceVersion", before.expectedSourceVersion()));
        } catch (RuntimeException auditFailure) {
            ephemeralEvidenceStore.evict(handle);
            throw auditFailure;
        }
        return LiveRetrievalResult.verified(documentId, handle, selection.locatorType(), selection.locatorValue(),
                selection.partialCoverage());
    }

    private static LiveRetrievalException mapContentFailure(SourceContentOutcome outcome) {
        return switch (outcome) {
            case DOCUMENT_CHANGED, VERSION_MISMATCH -> new LiveRetrievalException(
                    LiveRetrievalException.Reason.DOCUMENT_CHANGED);
            case UNSUPPORTED_FORMAT -> new LiveRetrievalException(LiveRetrievalException.Reason.UNSUPPORTED_FORMAT);
            case TIMEOUT -> new LiveRetrievalException(LiveRetrievalException.Reason.REQUEST_TIMEOUT);
            case EXPORT_LIMIT_EXCEEDED -> new LiveRetrievalException(LiveRetrievalException.Reason.EXPORT_LIMIT_EXCEEDED);
            default -> new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AVAILABLE);
        };
    }

    private static LiveRetrievalStatus mapStatus(LiveRetrievalException.Reason reason) {
        return switch (reason) {
            case NOT_AUTHORIZED -> LiveRetrievalStatus.NOT_AUTHORIZED;
            case NOT_AVAILABLE -> LiveRetrievalStatus.NOT_AVAILABLE;
            case DOCUMENT_CHANGED -> LiveRetrievalStatus.DOCUMENT_CHANGED;
            case NO_EVIDENCE -> LiveRetrievalStatus.NO_EVIDENCE;
            case UNSUPPORTED_FORMAT -> LiveRetrievalStatus.UNSUPPORTED_FORMAT;
            case CAPACITY_EXHAUSTED -> LiveRetrievalStatus.CAPACITY_EXHAUSTED;
            case EXPORT_LIMIT_EXCEEDED -> LiveRetrievalStatus.EXPORT_LIMIT_EXCEEDED;
            case REQUEST_TIMEOUT -> LiveRetrievalStatus.REQUEST_TIMEOUT;
        };
    }

    /**
     * {@code candidate}(Vector 검색이 준 Locator 힌트)가 새로 Parse한 콘텐츠에도
     * 여전히 존재하면 그 위치의 발췌를 진실하게 반환한다. 없거나(형식/버전 변화로
     * Locator가 더 이상 없음) 직접 선택 경로({@code candidate == null})면 첫 번째
     * 위치를 대표 발췌로 쓴다 - 여러 위치로 이뤄진 문서의 일부만 담았다는 사실을
     * {@code partialCoverage}로 정직하게 알린다("never assign a cross-page excerpt
     * solely to its starting page" - 반환되는 발췌는 항상 정확히 그 위치 하나의
     * 실제 Locator로만 표시되며, 여러 위치를 하나로 합쳐 표시하지 않는다).
     */
    private EvidenceSelection selectEvidence(ParseOutcome parsed, Long documentId, String sourceVersion,
            VectorCandidate candidate) {
        List<ExtractedLocation> locations = parsed.locations();
        if (locations.isEmpty()) {
            return null;
        }
        String text = parsed.normalizedText();
        if (candidate != null) {
            if (!documentId.equals(candidate.documentId()) || !sourceVersion.equals(candidate.sourceVersion())
                    || !parsed.parserVersion().equals(candidate.parserVersion())
                    || !parsed.chunkingVersion().equals(candidate.chunkingVersion())
                    || !parsed.embeddingModel().equals(candidate.modelVersion())) {
                return null;
            }
            return parsed.chunks().stream()
                    .filter(chunk -> chunk.chunkIndex() == candidate.chunkIndex()
                            && chunk.locatorType() == candidate.locatorType()
                            && chunk.locatorValue().equals(candidate.locatorValue()))
                    .findFirst()
                    .map(chunk -> excerptFrom(text, chunk.startOffset(), chunk.endOffset(), chunk.locatorType(),
                            chunk.locatorValue(), locations.size() > 1))
                    .orElse(null);
            // Candidate가 가리키던 위치가 이번 Parse 결과에는 없다(형식/버전 변화) - 근거
            // 없는 매칭을 지어내지 않고 아래 기본(첫 번째 위치) 경로로 넘어간다.
        }
        return excerptFrom(text, locations.get(0), locations.size() > 1);
    }

    private EvidenceSelection excerptFrom(String text, ExtractedLocation location, boolean documentHasMoreLocations) {
        return excerptFrom(text, location.startOffset(), location.endOffset(), location.locatorType(),
                location.locatorValue(), documentHasMoreLocations);
    }

    private EvidenceSelection excerptFrom(String text, int requestedStart, int requestedEnd, LocatorType locatorType,
            String locatorValue, boolean documentHasMoreLocations) {
        int start = Math.max(0, Math.min(requestedStart, text.length()));
        int end = Math.max(start, Math.min(requestedEnd, text.length()));
        String raw = text.substring(start, end);
        if (raw.isBlank()) {
            return null;
        }
        boolean truncated = raw.length() > properties.maxEvidenceChars();
        String bounded = truncated ? raw.substring(0, properties.maxEvidenceChars()) : raw;
        if (bounded.isBlank()) {
            return null;
        }
        return new EvidenceSelection(bounded, locatorType, locatorValue,
                documentHasMoreLocations || truncated);
    }

    private record EvidenceSelection(String text, LocatorType locatorType, String locatorValue,
            boolean partialCoverage) {
    }
}
