package com.sdv.rag.application;

import com.sdv.ai.application.AskDeadline;
import com.sdv.ai.application.AssistantIntent;
import com.sdv.ai.application.AssistantProperties;
import com.sdv.ai.application.AssistantRouter;
import com.sdv.ai.application.NaturalLanguageFileQueryParser;
import com.sdv.ai.application.PolicyEnforcedLlmGateway;
import com.sdv.ai.application.PromptComposer;
import com.sdv.ai.application.port.LlmPort;
import com.sdv.common.model.UserContext;
import com.sdv.rag.api.dto.RagAnswerResponse;
import com.sdv.rag.api.dto.RagCitation;
import com.sdv.rag.domain.CandidateSelectionResult;
import com.sdv.rag.domain.EvidenceBatchResult;
import com.sdv.rag.domain.EvidenceProvenance;
import com.sdv.rag.domain.EvidenceReleaseResult;
import com.sdv.rag.domain.EvidenceReleaseStatus;
import com.sdv.rag.domain.LiveRetrievalResult;
import com.sdv.rag.domain.LiveRetrievalStatus;
import com.sdv.rag.domain.VectorCandidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** M13/M14 guided, read-only answer orchestration over M12 verified ephemeral evidence. */
@Service
public class RagAnswerService {
    private final AssistantRouter router;
    private final NaturalLanguageFileQueryParser fileQueryParser;
    private final FileMetadataDiscoveryService fileDiscovery;
    private final RagRetrievalService retrieval;
    private final PromptComposer promptComposer;
    private final PolicyEnforcedLlmGateway llm;
    private final CitationAssembler citationAssembler;
    private final AssistantProperties properties;

    public RagAnswerService(AssistantRouter router, NaturalLanguageFileQueryParser fileQueryParser,
            FileMetadataDiscoveryService fileDiscovery, RagRetrievalService retrieval, PromptComposer promptComposer,
            PolicyEnforcedLlmGateway llm, CitationAssembler citationAssembler, AssistantProperties properties) {
        this.router = router;
        this.fileQueryParser = fileQueryParser;
        this.fileDiscovery = fileDiscovery;
        this.retrieval = retrieval;
        this.promptComposer = promptComposer;
        this.llm = llm;
        this.citationAssembler = citationAssembler;
        this.properties = properties;
    }

    public RagAnswerResponse ask(UserContext requester, String question, List<Long> selectedDocumentIds) {
        AskDeadline deadline = AskDeadline.startingNow(properties.totalDeadlineMs());
        boolean hasSelectedDocuments = selectedDocumentIds != null && !selectedDocumentIds.isEmpty();
        AssistantRouter.Route route = router.route(question, hasSelectedDocuments, deadline);
        if (route.intent() == AssistantIntent.POLICY_BYPASS)
            return RagAnswerResponse.failed("REJECTED", "POLICY_BYPASS");
        if (route.intent() == AssistantIntent.OUT_OF_SCOPE)
            return RagAnswerResponse.failed("REJECTED", "OUT_OF_SCOPE");
        if (route.intent() == AssistantIntent.CLARIFICATION_REQUIRED)
            return RagAnswerResponse.failed("CLARIFICATION_REQUIRED", route.reasonCode());
        if (route.intent() == AssistantIntent.FIND_FILE) {
            var parsed = fileQueryParser.parse(question, 20);
            if (parsed.failureReason() != null)
                return RagAnswerResponse.failed("CLARIFICATION_REQUIRED", parsed.failureReason());
            return new RagAnswerResponse("SUCCESS", null, null, null, List.of(),
                    fileDiscovery.search(requester, parsed.query()), false);
        }
        return answerFromContent(requester, question, selectedDocumentIds, route.intent(), deadline);
    }

    private RagAnswerResponse answerFromContent(UserContext requester, String question, List<Long> requestedIds,
            AssistantIntent intent, AskDeadline deadline) {
        List<Long> selected = requestedIds == null ? List.of()
                : requestedIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (selected.size() > properties.maxSelectedDocuments())
            return RagAnswerResponse.failed("FAILED", "SELECTION_LIMIT_EXCEEDED");

        String conversationId = retrieval.openEvidenceConversation(requester);
        boolean successful = false;
        try {
            CandidateSelectionResult shortlist = retrieval.retrieveCandidatesForDocuments(requester, question,
                    selected, properties.maxEvidenceSpans(), deadline.remainingMillis());
            if (shortlist.status() != CandidateSelectionResult.Status.SUCCESS) return fromCandidateFailure(shortlist.status());

            if (shortlist.candidates().isEmpty()) return RagAnswerResponse.failed("NO_EVIDENCE", "NO_RELEVANT_EVIDENCE");
            Set<Long> candidateDocuments = shortlist.candidates().stream().map(VectorCandidate::documentId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            boolean coveragePartial = shortlist.candidates().size() >= properties.maxEvidenceSpans()
                    || (!selected.isEmpty() && !candidateDocuments.containsAll(selected));
            if (intent == AssistantIntent.COMPARE && !selected.isEmpty()
                    && !candidateDocuments.containsAll(selected)) {
                return RagAnswerResponse.incomplete("COMPARISON_INPUT_INCOMPLETE");
            }
            EvidenceBatchResult batch = retrieval.retrieveVerifiedEvidenceCandidates(requester, conversationId,
                    shortlist.candidates(), deadline.remainingMillis());

            List<BoundEvidence> supplied = new ArrayList<>();
            String failure = null;
            coveragePartial |= batch.requestPartial();
            for (LiveRetrievalResult item : batch.results()) {
                if (item.status() != LiveRetrievalStatus.VERIFIED) {
                    failure = reason(item.status());
                    coveragePartial = true;
                    continue;
                }
                coveragePartial |= item.partialCoverage();
                EvidenceReleaseResult release = retrieval.releaseVerifiedEvidence(requester, item, conversationId,
                        deadline.remainingMillis());
                if (release.status() != EvidenceReleaseStatus.RELEASED) {
                    failure = reason(release.status());
                    coveragePartial = true;
                    continue;
                }
                String label = "E" + (supplied.size() + 1);
                supplied.add(new BoundEvidence(label, item, release));
            }
            if (supplied.isEmpty()) {
                String reason = failure == null ? "NO_RELEVANT_EVIDENCE" : failure;
                return RagAnswerResponse.failed("NO_EVIDENCE".equals(reason) ? "NO_EVIDENCE" : "FAILED", reason);
            }
            Set<Long> usableDocuments = supplied.stream().map(e -> e.live().documentId())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!selected.isEmpty() && !usableDocuments.containsAll(selected)) coveragePartial = true;
            if (intent == AssistantIntent.COMPARE && !selected.isEmpty() && !usableDocuments.containsAll(selected)) {
                return RagAnswerResponse.incomplete("COMPARISON_INPUT_INCOMPLETE");
            }

            PromptComposer.Composition composition = promptComposer.compose(question, supplied.stream()
                    .map(e -> new LlmPort.EvidenceInput(e.label(), e.release().text())).toList());
            if (composition.failureReason() != null)
                return RagAnswerResponse.failed("FAILED", composition.failureReason());

            Set<String> actuallySupplied = composition.request().evidence().stream()
                    .map(LlmPort.EvidenceInput::label).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            supplied = supplied.stream().filter(e -> actuallySupplied.contains(e.label())).toList();
            coveragePartial |= composition.partial();
            if (intent == AssistantIntent.COMPARE && !selected.isEmpty()
                    && !supplied.stream().map(e -> e.live().documentId()).collect(java.util.stream.Collectors.toSet())
                            .containsAll(selected)) {
                return RagAnswerResponse.incomplete("COMPARISON_INPUT_INCOMPLETE");
            }
            var generated = llm.generate(composition.request(), deadline);
            if (generated.failure() != PolicyEnforcedLlmGateway.Failure.NONE)
                return RagAnswerResponse.failed("FAILED", modelFailure(generated.failure()));

            List<BoundEvidence> finalSupplied = supplied;
            List<LlmPort.Claim> supported = generated.value().claims().stream()
                    .filter(claim -> supports(claim, finalSupplied)).toList();
            if (supported.isEmpty()) return RagAnswerResponse.failed("NO_EVIDENCE", "UNSUPPORTED_MODEL_CLAIMS");

            // Revalidate every supplied span, including labels the model did not cite.
            for (BoundEvidence evidence : supplied) {
                EvidenceReleaseResult current = retrieval.releaseVerifiedEvidence(requester, evidence.live(),
                        conversationId, deadline.remainingMillis());
                if (current.status() != EvidenceReleaseStatus.RELEASED
                        || !sameBinding(evidence.release().provenance(), current.provenance())) {
                    return RagAnswerResponse.failed("FAILED", reason(current.status()));
                }
            }
            for (BoundEvidence evidence : supplied) {
                if (!retrieval.validateEvidenceForResponse(requester, conversationId, evidence.live(),
                        evidence.release().provenance())) {
                    return RagAnswerResponse.failed("FAILED", "NOT_AUTHORIZED");
                }
            }

            Map<String, BoundEvidence> byLabel = new LinkedHashMap<>();
            supplied.forEach(e -> byLabel.put(e.label(), e));
            LinkedHashSet<String> cited = new LinkedHashSet<>();
            supported.forEach(c -> cited.addAll(c.evidenceLabels()));
            List<RagCitation> citations = cited.stream().map(byLabel::get).filter(java.util.Objects::nonNull)
                    .map(e -> citationAssembler.assemble(requester, e.release().provenance())).toList();
            String answer = supported.stream().map(LlmPort.Claim::text).reduce((a, b) -> a + "\n" + b).orElse(null);
            successful = true;
            return new RagAnswerResponse(coveragePartial ? "PARTIAL" : "SUCCESS",
                    coveragePartial ? (failure == null ? "PARTIAL_EVIDENCE_COVERAGE" : failure) : null,
                    answer, generated.value().generatedAnalysis(), citations, null, coveragePartial);
        } finally {
            if (successful) retrieval.closeEvidenceConversation(requester, conversationId);
            else retrieval.failEvidenceConversation(requester, conversationId);
        }
    }

    private static boolean sameBinding(EvidenceProvenance a, EvidenceProvenance b) {
        return a.documentId().equals(b.documentId()) && a.sourceId().equals(b.sourceId())
                && a.shareId().equals(b.shareId()) && a.shareGeneration() == b.shareGeneration()
                && a.connectionGeneration() == b.connectionGeneration() && a.sourceVersion().equals(b.sourceVersion())
                && a.locatorType() == b.locatorType() && a.locatorValue().equals(b.locatorValue())
                && a.createdAt().equals(b.createdAt()) && a.expiresAt().equals(b.expiresAt());
    }

    private static boolean supports(LlmPort.Claim claim, List<BoundEvidence> evidence) {
        if (claim.text() == null || claim.text().isBlank() || claim.evidenceLabels() == null
                || claim.evidenceLabels().isEmpty() || claim.supportingQuotes() == null
                || claim.supportingQuotes().isEmpty()) return false;
        Map<String, String> byLabel = new LinkedHashMap<>();
        evidence.forEach(e -> byLabel.put(e.label(), e.release().text()));
        for (String label : claim.evidenceLabels()) if (!byLabel.containsKey(label)) return false;
        String quotes = String.join(" ", claim.supportingQuotes());
        for (String quote : claim.supportingQuotes()) {
            if (quote == null || quote.isBlank()
                    || claim.evidenceLabels().stream().map(byLabel::get).noneMatch(text -> text.contains(quote))) return false;
        }
        if (contradictory(quotes)) return false;
        Set<String> claimTerms = significantTerms(claim.text());
        Set<String> quoteTerms = significantTerms(quotes);
        claimTerms.retainAll(quoteTerms);
        return !claimTerms.isEmpty();
    }

    private static boolean contradictory(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return (lower.contains("허용") && lower.contains("금지"))
                || (lower.contains("allowed") && (lower.contains("forbidden") || lower.contains("denied")));
    }

    private static Set<String> significantTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (term.length() >= 2 && !Set.of("그리고", "대한", "있다", "the", "and", "that", "with").contains(term)) terms.add(term);
        }
        return terms;
    }

    private static RagAnswerResponse fromCandidateFailure(CandidateSelectionResult.Status status) {
        return switch (status) {
            case NOT_AUTHORIZED -> RagAnswerResponse.failed("FAILED", "NOT_AUTHORIZED");
            case REQUEST_TIMEOUT -> RagAnswerResponse.failed("FAILED", "REQUEST_TIMEOUT");
            case NOT_AVAILABLE -> RagAnswerResponse.failed("FAILED", "PROVIDER_UNAVAILABLE");
            case NO_EVIDENCE -> RagAnswerResponse.failed("NO_EVIDENCE", "NO_RELEVANT_EVIDENCE");
            case SUCCESS -> throw new IllegalArgumentException("success is not a failure");
        };
    }

    private static String modelFailure(PolicyEnforcedLlmGateway.Failure failure) {
        return switch (failure) {
            case TIMEOUT -> "REQUEST_TIMEOUT";
            case CAPACITY_EXHAUSTED -> "CAPACITY_EXHAUSTED";
            case MODEL_UNAVAILABLE -> "MODEL_UNAVAILABLE";
            case NONE -> null;
        };
    }

    private static String reason(LiveRetrievalStatus status) {
        return switch (status) {
            case NOT_AUTHORIZED -> "NOT_AUTHORIZED";
            case NOT_AVAILABLE -> "PROVIDER_UNAVAILABLE";
            case DOCUMENT_CHANGED -> "DOCUMENT_CHANGED";
            case NO_EVIDENCE -> "NO_EVIDENCE";
            case UNSUPPORTED_FORMAT -> "UNSUPPORTED_FORMAT";
            case CAPACITY_EXHAUSTED -> "CAPACITY_EXHAUSTED";
            case EXPORT_LIMIT_EXCEEDED -> "EXPORT_LIMIT_EXCEEDED";
            case REQUEST_TIMEOUT -> "REQUEST_TIMEOUT";
            case VERIFIED -> null;
        };
    }

    private static String reason(EvidenceReleaseStatus status) {
        return switch (status) {
            case NOT_AUTHORIZED -> "NOT_AUTHORIZED";
            case DOCUMENT_CHANGED -> "DOCUMENT_CHANGED";
            case REQUEST_TIMEOUT -> "REQUEST_TIMEOUT";
            case EXPIRED -> "EVIDENCE_EXPIRED";
            case UNAVAILABLE -> "EVIDENCE_UNAVAILABLE";
            case RELEASED -> null;
        };
    }

    private record BoundEvidence(String label, LiveRetrievalResult live, EvidenceReleaseResult release) { }
}
