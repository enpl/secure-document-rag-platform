package com.sdv.rag.application;

import com.sdv.common.model.UserContext;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.policy.domain.PolicyDecision;
import com.sdv.rag.application.port.VectorSearchPort;
import com.sdv.rag.application.port.out.EphemeralEvidenceStore;
import com.sdv.rag.domain.CandidateSelectionResult;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.QueryEmbeddingOutcome;
import com.sdv.rag.domain.VectorCandidate;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.SliceImpl;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagRetrievalCandidateSelectionTest {
    private static final UserContext USER = new UserContext("recipient", "r@example.test", Set.of(), Set.of());

    @Test
    void absentSelectionUsesOnlyTheFreshAuthorizedScopeForVectorSearch() {
        Fixture fixture = fixtureWithAuthorizedDocument();

        CandidateSelectionResult result = fixture.service.retrieveCandidatesForDocuments(USER, "질문", List.of(),
                5, 1_000);

        assertThat(result.status()).isEqualTo(CandidateSelectionResult.Status.SUCCESS);
        assertThat(result.candidates()).extracting(VectorCandidate::documentId).containsExactly(11L);
        verify(fixture.vectorSearch).searchAllowed(org.mockito.ArgumentMatchers.eq(Set.of(11L)), any(), anyInt());
    }

    @Test
    void absentSelectionWithNoAuthorizedScopeNeverEmbedsOrSearchesGlobally() {
        DocumentShareJpaRepository shares = mock(DocumentShareJpaRepository.class);
        when(shares.searchSharedDiscoverable(anyString(), any(Boolean.class), anyLong(), any(Boolean.class),
                anyString(), any(Boolean.class), anyString(), any(Boolean.class), any(), any(Boolean.class), any(), any()))
                .thenReturn(new SliceImpl<>(List.of()));
        DocumentParsingClient parser = mock(DocumentParsingClient.class);
        VectorSearchPort vector = mock(VectorSearchPort.class);
        RagRetrievalService service = service(shares, mock(EffectivePermissionService.class), vector, parser,
                mock(SourceDocumentJpaRepository.class));

        CandidateSelectionResult result = service.retrieveCandidatesForDocuments(USER, "질문", List.of(), 5, 1_000);

        assertThat(result.status()).isEqualTo(CandidateSelectionResult.Status.NO_EVIDENCE);
        verify(parser, never()).embedQuery(anyString(), anyLong());
        verify(vector, never()).searchAllowed(any(), any(), anyInt());
    }

    private static Fixture fixtureWithAuthorizedDocument() {
        DocumentShareJpaRepository shares = mock(DocumentShareJpaRepository.class);
        EffectivePermissionService permissions = mock(EffectivePermissionService.class);
        VectorSearchPort vector = mock(VectorSearchPort.class);
        DocumentParsingClient parser = mock(DocumentParsingClient.class);
        SourceDocumentJpaRepository documents = mock(SourceDocumentJpaRepository.class);
        SourceDocumentEntity document = mock(SourceDocumentEntity.class);
        DocumentShareEntity share = mock(DocumentShareEntity.class);
        when(document.getId()).thenReturn(11L);
        when(document.getSourceId()).thenReturn(1L);
        when(document.getState()).thenReturn("ACTIVE");
        when(document.getIndexStatus()).thenReturn("INDEXED");
        when(document.getSourceVersion()).thenReturn("v-current");
        when(share.getId()).thenReturn(91L);
        when(share.getGeneration()).thenReturn(3L);
        var candidate = new DocumentShareJpaRepository.SharedDiscoveryCandidate(document, share, "publisher", 4L);
        when(shares.searchSharedDiscoverable(anyString(), any(Boolean.class), anyLong(), any(Boolean.class),
                anyString(), any(Boolean.class), anyString(), any(Boolean.class), any(), any(Boolean.class), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(candidate)));
        when(permissions.evaluateSharedAccess(any(), any(), any())).thenReturn(PolicyDecision.allow());
        when(parser.embedQuery(anyString(), anyLong())).thenReturn(QueryEmbeddingOutcome.success(new float[]{1f}));
        VectorCandidate hit = new VectorCandidate(11L, 7, LocatorType.SECTION, "later", "v-current",
                "parser-v1", "chunk-v2", "bge-m3:567m");
        when(vector.searchAllowed(any(), any(), anyInt())).thenReturn(List.of(hit));
        when(documents.findById(11L)).thenReturn(java.util.Optional.of(document));
        return new Fixture(service(shares, permissions, vector, parser, documents), vector);
    }

    private static RagRetrievalService service(DocumentShareJpaRepository shares,
            EffectivePermissionService permissions, VectorSearchPort vector, DocumentParsingClient parser,
            SourceDocumentJpaRepository documents) {
        return new RagRetrievalService(shares, permissions, vector, parser,
                mock(LiveEvidenceRetrievalService.class), mock(SourceConsistencyGuard.class),
                mock(EphemeralEvidenceStore.class), new LiveRetrievalProperties(5, 20_000, 60_000, 4_000, 10, 500),
                documents, mock(EvidenceConversationLifecycle.class));
    }

    private record Fixture(RagRetrievalService service, VectorSearchPort vectorSearch) { }
}
