package com.sdv.rag.application;

import com.sdv.common.model.UserContext;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.policy.domain.AiRequestContext;
import com.sdv.rag.application.port.VectorSearchPort;
import com.sdv.rag.application.port.out.EphemeralEvidenceStore;
import com.sdv.rag.domain.EvidenceBatchResult;
import com.sdv.rag.domain.EvidenceKey;
import com.sdv.rag.domain.EvidenceProvenance;
import com.sdv.rag.domain.EvidenceReleaseResult;
import com.sdv.rag.domain.EvidenceReleaseStatus;
import com.sdv.rag.domain.CandidateSelectionResult;
import com.sdv.rag.domain.LiveRetrievalResult;
import com.sdv.rag.domain.LiveRetrievalStatus;
import com.sdv.rag.domain.QueryEmbeddingOutcome;
import com.sdv.rag.domain.VectorCandidate;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.source.domain.ShareAction;
import com.sdv.source.domain.SourceAccessContext;
import com.sdv.source.domain.SourceDocumentState;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository.SharedDiscoveryCandidate;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F-BE-100(M12 신규, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.4/§2A.5). Vector
 * Candidate 조회({@link #retrieveCandidates})와 검증된 근거 조회({@link
 * #retrieveVerifiedEvidence})의 진입점. {@link #retrieveCandidates}는 ACL
 * Catalog Prefilter + Embedding-only Shortlist일 뿐, 그 자체로 권한 판단이나
 * 답변 근거가 아니다(v1.4 §2A.4) - 실제 인가/근거는 {@link
 * #retrieveVerifiedEvidence}가 {@link LiveEvidenceRetrievalService}로 위임하는
 * Mandatory Live Retrieval 한 경로에서만 만들어진다. 직접 선택된 파일 ID도
 * (Vector 후보 없이) 이 동일한 경로를 거친다 - Candidate를 우회하지 않는다.
 */
@Service
public class RagRetrievalService {

    private static final String NO_FILTER_SENTINEL = "";
    private static final long NO_SOURCE_ID_SENTINEL = 0L;

    private final DocumentShareJpaRepository documentShareJpaRepository;
    private final EffectivePermissionService effectivePermissionService;
    private final VectorSearchPort vectorSearchPort;
    private final DocumentParsingClient documentParsingClient;
    private final LiveEvidenceRetrievalService liveEvidenceRetrievalService;
    private final SourceConsistencyGuard sourceConsistencyGuard;
    private final EphemeralEvidenceStore ephemeralEvidenceStore;
    private final LiveRetrievalProperties properties;
    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final EvidenceConversationLifecycle conversationLifecycle;

    public RagRetrievalService(DocumentShareJpaRepository documentShareJpaRepository,
            EffectivePermissionService effectivePermissionService, VectorSearchPort vectorSearchPort,
            DocumentParsingClient documentParsingClient, LiveEvidenceRetrievalService liveEvidenceRetrievalService,
            SourceConsistencyGuard sourceConsistencyGuard, EphemeralEvidenceStore ephemeralEvidenceStore,
            LiveRetrievalProperties properties, SourceDocumentJpaRepository sourceDocumentJpaRepository,
            EvidenceConversationLifecycle conversationLifecycle) {
        this.documentShareJpaRepository = documentShareJpaRepository;
        this.effectivePermissionService = effectivePermissionService;
        this.vectorSearchPort = vectorSearchPort;
        this.documentParsingClient = documentParsingClient;
        this.liveEvidenceRetrievalService = liveEvidenceRetrievalService;
        this.sourceConsistencyGuard = sourceConsistencyGuard;
        this.ephemeralEvidenceStore = ephemeralEvidenceStore;
        this.properties = properties;
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.conversationLifecycle = conversationLifecycle;
    }

    /**
     * ACL Catalog Prefilter(요청자 SDV 공유 + Local AI Usage Policy, §2A.4/§2A.5)로
     * 계산한 허용 문서 ID 범위 안에서만 Vector Candidate를 반환한다. 빈 질의/
     * 허용 문서 없음/Embedding 생성 실패는 모두 빈 목록으로 안전하게 끝난다 - 예외를
     * 던지지 않는다(Candidate 조회는 그 자체로 민감한 실패 정보를 노출할 필요가
     * 없다).
     */
    public List<VectorCandidate> retrieveCandidates(UserContext requester, String queryText, int topK) {
        if (requester == null || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        Map<Long, SourceAccessContext> allowed = resolveAllowedDocuments(requester);
        if (allowed.isEmpty()) {
            return List.of();
        }
        QueryEmbeddingOutcome embedding = documentParsingClient.embedQuery(queryText);
        if (!embedding.success()) {
            return List.of();
        }
        int boundedTopK = Math.max(1, Math.min(topK, properties.maxTopK()));
        Map<Long, SourceAccessContext> currentAllowed = resolveAllowedDocuments(requester);
        if (currentAllowed.isEmpty()) {
            return List.of();
        }
        return vectorSearchPort.searchAllowed(currentAllowed.keySet(), embedding.embedding(), boundedTopK).stream()
                .filter(candidate -> isCurrentCandidate(candidate, currentAllowed)).toList();
    }

    /**
     * M13 selected-document path. Every requested id must be authorized both before and after
     * bounded query embedding; vector search never receives an empty/global filter.
     */
    public CandidateSelectionResult retrieveCandidatesForDocuments(UserContext requester, String queryText,
            List<Long> selectedDocumentIds, int topK, long deadlineMs) {
        if (requester == null || queryText == null || queryText.isBlank()) {
            return CandidateSelectionResult.failed(CandidateSelectionResult.Status.NO_EVIDENCE);
        }
        if (deadlineMs <= 0) return CandidateSelectionResult.failed(CandidateSelectionResult.Status.REQUEST_TIMEOUT);
        List<Long> selected = selectedDocumentIds == null ? List.of()
                : selectedDocumentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        boolean restrictedSelection = !selected.isEmpty();
        LiveRetrievalDeadline deadline = LiveRetrievalDeadline.startingNow(deadlineMs);
        Map<Long, SourceAccessContext> allowed = resolveAllowedDocuments(requester);
        if (allowed.isEmpty()) return CandidateSelectionResult.failed(CandidateSelectionResult.Status.NO_EVIDENCE);
        if (restrictedSelection && !allowed.keySet().containsAll(selected)) {
            return CandidateSelectionResult.failed(CandidateSelectionResult.Status.NOT_AUTHORIZED);
        }
        QueryEmbeddingOutcome embedding = documentParsingClient.embedQuery(queryText, deadline.remainingMillis());
        if (!embedding.success()) {
            return CandidateSelectionResult.failed(deadline.expired()
                    || "request deadline expired".equals(embedding.reason())
                    ? CandidateSelectionResult.Status.REQUEST_TIMEOUT
                    : CandidateSelectionResult.Status.NOT_AVAILABLE);
        }
        if (deadline.expired()) return CandidateSelectionResult.failed(CandidateSelectionResult.Status.REQUEST_TIMEOUT);
        Map<Long, SourceAccessContext> currentAllowed = resolveAllowedDocuments(requester);
        if (currentAllowed.isEmpty()) return CandidateSelectionResult.failed(CandidateSelectionResult.Status.NO_EVIDENCE);
        if (restrictedSelection && !currentAllowed.keySet().containsAll(selected)) {
            return CandidateSelectionResult.failed(CandidateSelectionResult.Status.NOT_AUTHORIZED);
        }
        java.util.Set<Long> searchScope = restrictedSelection
                ? new java.util.LinkedHashSet<>(selected)
                : new java.util.LinkedHashSet<>(currentAllowed.keySet());
        int boundedTopK = Math.max(1, Math.min(topK, properties.maxTopK()));
        List<VectorCandidate> candidates = vectorSearchPort.searchAllowed(searchScope,
                embedding.embedding(), boundedTopK).stream()
                .filter(candidate -> searchScope.contains(candidate.documentId()))
                .filter(candidate -> isCurrentCandidate(candidate, currentAllowed)).toList();
        return CandidateSelectionResult.success(candidates);
    }

    /**
     * 여러 파일(Vector Candidate 유래 또는 직접 선택)에 대해 Mandatory Live
     * Retrieval을 순차 수행한다. {@code candidatesByDocumentId}에 없는 문서 ID는
     * 직접 선택으로 취급된다(Locator 힌트 없음). 파일 수/총 시간 예산 상한에
     * 도달하면 그 이후 파일은 시도하지 않고 {@code requestPartial=true}로 정직하게
     * 보고한다 - 이미 확정된 앞선 결과는 그대로 유지한다.
     */
    public EvidenceBatchResult retrieveVerifiedEvidence(UserContext requester, String conversationId,
            List<Long> documentIds, Map<Long, VectorCandidate> candidatesByDocumentId) {
        return retrieveVerifiedEvidence(requester, conversationId, documentIds, candidatesByDocumentId,
                properties.totalRequestDeadlineMs());
    }

    /** Budget-aware overload for an enclosing M13/M14 ask operation. */
    public EvidenceBatchResult retrieveVerifiedEvidence(UserContext requester, String conversationId,
            List<Long> documentIds, Map<Long, VectorCandidate> candidatesByDocumentId, long deadlineMs) {
        EvidenceConversationLifecycle.Lease lease;
        try {
            lease = conversationLifecycle.requireActive(requester, conversationId);
        } catch (LiveRetrievalException denied) {
            return new EvidenceBatchResult(List.of(), true);
        }
        List<LiveRetrievalResult> results = new ArrayList<>();
        boolean requestPartial = false;
        LiveRetrievalDeadline deadline = LiveRetrievalDeadline.startingNow(
                Math.min(properties.totalRequestDeadlineMs(), Math.max(1L, deadlineMs)));
        int limit = Math.min(documentIds.size(), properties.maxFilesPerRequest());
        if (documentIds.size() > properties.maxFilesPerRequest()) {
            requestPartial = true;
        }
        for (int i = 0; i < limit; i++) {
            if (!conversationLifecycle.isActive(lease) || deadline.expired()) {
                requestPartial = true;
                break;
            }
            Long documentId = documentIds.get(i);
            VectorCandidate candidate = candidatesByDocumentId == null ? null
                    : candidatesByDocumentId.get(documentId);
            results.add(liveEvidenceRetrievalService.retrieveLive(requester, documentId, conversationId, candidate,
                    deadline));
        }
        return new EvidenceBatchResult(results, requestPartial);
    }

    /**
     * M13 bounded span path: each server-side vector candidate is live-fetched and matched by
     * exact generation/chunk. It never invokes M12's candidate-null first-location behavior.
     */
    public EvidenceBatchResult retrieveVerifiedEvidenceCandidates(UserContext requester, String conversationId,
            List<VectorCandidate> candidates, long deadlineMs) {
        if (deadlineMs <= 0 || candidates == null || candidates.isEmpty()) {
            return new EvidenceBatchResult(List.of(), true);
        }
        EvidenceConversationLifecycle.Lease lease;
        try {
            lease = conversationLifecycle.requireActive(requester, conversationId);
        } catch (LiveRetrievalException denied) {
            return new EvidenceBatchResult(List.of(), true);
        }
        LiveRetrievalDeadline deadline = LiveRetrievalDeadline.startingNow(
                Math.min(properties.totalRequestDeadlineMs(), Math.max(1L, deadlineMs)));
        List<LiveRetrievalResult> results = new ArrayList<>();
        int limit = Math.min(candidates.size(), properties.maxFilesPerRequest());
        boolean partial = candidates.size() > limit;
        for (int i = 0; i < limit; i++) {
            if (deadline.expired() || !conversationLifecycle.isActive(lease)) { partial = true; break; }
            VectorCandidate candidate = candidates.get(i);
            results.add(liveEvidenceRetrievalService.retrieveLive(requester, candidate.documentId(), conversationId,
                    candidate, deadline));
        }
        return new EvidenceBatchResult(results, partial);
    }

    /**
     * 미래 답변(LLM) 계층이 실제로 근거 Text를 쓰기 직전에 호출해야 하는 최종
     * 공개 경계다("Expose a usable internal evidence-release/revalidation boundary
     * for the future answer layer" - 이 작업 지시사항). 매 호출마다 요청자 SDV
     * 공유 + 게시자 Provider 접근/버전을 새로 재확인한 뒤에만 {@link
     * EphemeralEvidenceStore#getIfAuthorizedAndCurrent}로 복호화한다 - 유효하지
     * 않은 Cache Hit을 반환하지 않는다(Stale Fallback 없음). 이 메서드 자체는
     * 아직 존재하지 않는 LLM 호출을 수행하지 않는다 - 호출자가 반환된 Text를
     * Prompt에 넣기 직전까지 최소한으로 보관해야 한다.
     */
    public Optional<String> releaseEvidence(UserContext requester, Long documentId, String conversationId,
            com.sdv.rag.domain.EvidenceHandle handle) {
        EvidenceReleaseResult released = releaseEvidenceInternal(requester, documentId, conversationId, handle,
                null, null, properties.perFileDeadlineMs());
        return released.status() == EvidenceReleaseStatus.RELEASED ? Optional.of(released.text()) : Optional.empty();
    }

    /** Typed final-use boundary used by M14; provenance and plaintext come from one fresh check. */
    public EvidenceReleaseResult releaseVerifiedEvidence(UserContext requester, LiveRetrievalResult evidence,
            String conversationId, long deadlineMs) {
        if (evidence == null || evidence.status() != LiveRetrievalStatus.VERIFIED) {
            return EvidenceReleaseResult.failed(EvidenceReleaseStatus.UNAVAILABLE);
        }
        return releaseEvidenceInternal(requester, evidence.documentId(), conversationId, evidence.evidenceHandle(),
                evidence.locatorType(), evidence.locatorValue(), deadlineMs);
    }

    private EvidenceReleaseResult releaseEvidenceInternal(UserContext requester, Long documentId,
            String conversationId, com.sdv.rag.domain.EvidenceHandle handle,
            com.sdv.rag.domain.LocatorType locatorType, String locatorValue, long deadlineMs) {
        if (handle == null) return EvidenceReleaseResult.failed(EvidenceReleaseStatus.UNAVAILABLE);
        if (deadlineMs <= 0) return EvidenceReleaseResult.failed(EvidenceReleaseStatus.REQUEST_TIMEOUT);
        if (!Instant.now().isBefore(handle.expiresAt())) {
            ephemeralEvidenceStore.evict(handle);
            return EvidenceReleaseResult.failed(EvidenceReleaseStatus.EXPIRED);
        }
        EvidenceConversationLifecycle.Lease lease;
        SourceConsistencyGuard.LiveIdentity fresh;
        LiveRetrievalDeadline deadline = LiveRetrievalDeadline.startingNow(deadlineMs);
        try {
            lease = conversationLifecycle.requireActive(requester, conversationId);
            fresh = sourceConsistencyGuard.verifyBefore(requester, documentId,
                    deadline.fileBudgetMillis(properties.perFileDeadlineMs()));
            sourceConsistencyGuard.verifyAfter(fresh, deadline.fileBudgetMillis(properties.perFileDeadlineMs()));
            if (deadline.expired()) {
                throw new LiveRetrievalException(LiveRetrievalException.Reason.REQUEST_TIMEOUT);
            }
            if (!conversationLifecycle.isActive(lease)) {
                throw new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AUTHORIZED);
            }
        } catch (LiveRetrievalException e) {
            ephemeralEvidenceStore.evict(handle);
            return EvidenceReleaseResult.failed(switch (e.reason()) {
                case NOT_AUTHORIZED -> EvidenceReleaseStatus.NOT_AUTHORIZED;
                case DOCUMENT_CHANGED -> EvidenceReleaseStatus.DOCUMENT_CHANGED;
                case REQUEST_TIMEOUT -> EvidenceReleaseStatus.REQUEST_TIMEOUT;
                default -> EvidenceReleaseStatus.UNAVAILABLE;
            });
        }
        EvidenceKey currentBinding = new EvidenceKey(requester.subject(), conversationId,
                fresh.context().sourceId(), documentId, fresh.context().shareId(), fresh.context().shareGeneration(),
                fresh.context().connectionGeneration(), fresh.context().requesterAuthorizationRevision(),
                fresh.expectedSourceVersion());
        long sourceFence = ephemeralEvidenceStore.captureSourceFence(fresh.context().sourceId());
        long conversationFence = ephemeralEvidenceStore.captureConversationFence(requester.subject(), conversationId);
        Optional<byte[]> bytes = ephemeralEvidenceStore.getIfAuthorizedAndCurrentFenced(handle, currentBinding,
                sourceFence, conversationFence);
        if (bytes.isEmpty()) return EvidenceReleaseResult.failed(
                Instant.now().isBefore(handle.expiresAt()) ? EvidenceReleaseStatus.UNAVAILABLE
                        : EvidenceReleaseStatus.EXPIRED);
        EvidenceProvenance provenance = new EvidenceProvenance(documentId, fresh.context().sourceId(),
                fresh.context().publisherSubject(), fresh.context().shareId(), fresh.context().shareGeneration(), fresh.context().connectionGeneration(),
                fresh.context().requesterAuthorizationRevision(), fresh.expectedSourceVersion(), locatorType, locatorValue, handle.createdAt(), handle.expiresAt(),
                Instant.now());
        byte[] plaintext = bytes.get();
        try {
            return EvidenceReleaseResult.released(new String(plaintext, StandardCharsets.UTF_8), provenance);
        } finally {
            java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Final local release fence after all provider checks. This is the response linearization
     * boundary; invalidation that wins before it prevents output, while bytes already returned
     * to the HTTP caller cannot be recalled.
     */
    public boolean validateEvidenceForResponse(UserContext requester, String conversationId,
            LiveRetrievalResult evidence, EvidenceProvenance provenance) {
        if (evidence == null || provenance == null || evidence.status() != LiveRetrievalStatus.VERIFIED
                || !Instant.now().isBefore(provenance.expiresAt())) return false;
        EvidenceConversationLifecycle.Lease lease;
        try {
            lease = conversationLifecycle.requireActive(requester, conversationId);
        } catch (LiveRetrievalException denied) {
            return false;
        }
        SourceAccessContext context = new SourceAccessContext(requester.subject(), provenance.publisherSubject(),
                provenance.sourceId(), provenance.documentId(), provenance.shareId(), ShareAction.VIEW,
                provenance.shareGeneration(), provenance.connectionGeneration(),
                provenance.requesterAuthorizationRevision());
        if (effectivePermissionService.evaluateSharedAccess(requester, context, AiRequestContext.local()).isDenied()
                || !conversationLifecycle.isActive(lease)) return false;
        EvidenceKey binding = new EvidenceKey(requester.subject(), conversationId, provenance.sourceId(),
                provenance.documentId(), provenance.shareId(), provenance.shareGeneration(),
                provenance.connectionGeneration(), provenance.requesterAuthorizationRevision(), provenance.sourceVersion());
        long sourceFence = ephemeralEvidenceStore.captureSourceFence(provenance.sourceId());
        long conversationFence = ephemeralEvidenceStore.captureConversationFence(requester.subject(), conversationId);
        Optional<byte[]> current = ephemeralEvidenceStore.getIfAuthorizedAndCurrentFenced(evidence.evidenceHandle(),
                binding, sourceFence, conversationFence);
        current.ifPresent(bytes -> java.util.Arrays.fill(bytes, (byte) 0));
        return current.isPresent() && conversationLifecycle.isActive(lease);
    }

    public String openEvidenceConversation(UserContext requester) {
        return conversationLifecycle.open(requester);
    }

    public void closeEvidenceConversation(UserContext requester, String conversationId) {
        conversationLifecycle.close(requester, conversationId);
    }

    public void cancelEvidenceConversation(UserContext requester, String conversationId) {
        conversationLifecycle.cancel(requester, conversationId);
    }

    public void failEvidenceConversation(UserContext requester, String conversationId) {
        conversationLifecycle.terminalError(requester, conversationId);
    }

    /**
     * §2A.4/§2A.5 - 요청자에게 명시적으로 활성 공유된(VIEW) + Local AI Usage
     * Policy를 통과한 + 현재 색인이 완료된(compatible generation) 문서만 Vector
     * 검색 대상이 된다. Legacy Owner-Only {@code filterAllowed}는 B-Model
     * 인가로 쓰지 않는다({@link EffectivePermissionService#evaluateSharedAccess}만
     * 재사용한다) - {@code FileMetadataDiscoveryService}와 동일한 후보 조회를
     * 재사용하되, 여기서는 Live Google 재확인을 하지 않는다(그건 Vector Ranking
     * 이후 Mandatory Live Retrieval의 몫이다 - 후보 단계에서 매 문서마다 Google을
     * 부르면 사용자당 공유 문서 수에 비례해 불필요한 Live 호출이 발생한다).
     */
    private Map<Long, SourceAccessContext> resolveAllowedDocuments(UserContext requester) {
        Map<Long, SourceAccessContext> allowed = new LinkedHashMap<>();
        java.util.Optional<com.sdv.identity.domain.UserAuthorizationSnapshot> authorization =
                effectivePermissionService.currentSharedAuthorization(requester);
        if (authorization.isEmpty()) return allowed;
        int clearanceRank = authorization.get().maximumClassification().rank();
        long authorizationRevision = authorization.get().authorizationRevision();
        int scanned = 0;
        int page = 0;
        while (scanned < properties.maxCandidateDocumentScan()) {
            Pageable pageable = PageRequest.of(page, 100, Sort.by(Sort.Direction.ASC, "id"));
            Slice<SharedDiscoveryCandidate> slice = documentShareJpaRepository.searchSharedDiscoverable(
                    requester.subject(), clearanceRank, false, NO_SOURCE_ID_SENTINEL, false, NO_FILTER_SENTINEL,
                    false, NO_FILTER_SENTINEL, false, Instant.EPOCH, false, Instant.EPOCH, pageable);
            List<SharedDiscoveryCandidate> batch = slice.getContent();
            if (batch.isEmpty()) {
                break;
            }
            for (SharedDiscoveryCandidate candidate : batch) {
                if (scanned >= properties.maxCandidateDocumentScan()) {
                    break;
                }
                scanned++;
                SourceDocumentEntity document = candidate.document();
                if (!isCompatiblyIndexed(document)) {
                    continue;
                }
                SourceAccessContext context = new SourceAccessContext(requester.subject(),
                        candidate.publisherSubject(), document.getSourceId(), document.getId(),
                        candidate.share().getId(), ShareAction.VIEW, candidate.share().getGeneration(),
                        candidate.connectionEpoch(), authorizationRevision);
                if (effectivePermissionService.evaluateSharedAccess(requester, context, AiRequestContext.local())
                        .isAllowed()) {
                    allowed.put(document.getId(), context);
                }
            }
            if (!slice.hasNext()) {
                break;
            }
            page++;
        }
        return allowed;
    }

    /** 삭제되지 않았고, 색인이 완료된(현재 Source Version 기준) 문서만 Vector 검색 후보다. */
    private static boolean isCompatiblyIndexed(SourceDocumentEntity document) {
        return SourceDocumentState.ACTIVE.name().equals(document.getState())
                && "INDEXED".equals(document.getIndexStatus());
    }

    private boolean isCurrentCandidate(VectorCandidate candidate, Map<Long, SourceAccessContext> allowed) {
        if (!allowed.containsKey(candidate.documentId())) {
            return false;
        }
        return sourceDocumentJpaRepository.findById(candidate.documentId())
                .filter(document -> isCompatiblyIndexed(document)
                        && candidate.sourceVersion().equals(document.getSourceVersion()))
                .isPresent();
    }
}
