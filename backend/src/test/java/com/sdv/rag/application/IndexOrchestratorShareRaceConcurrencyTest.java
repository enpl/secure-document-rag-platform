package com.sdv.rag.application;

import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import com.sdv.rag.domain.EmbeddingChunk;
import com.sdv.rag.domain.IndexOutcome;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.source.application.SourceSharingService;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * M11 후속 교정 검증(이 작업 지시사항 2번) - {@link IndexOrchestratorDisconnectConcurrencyTest}와
 * 같은 기법(실제 Proxied Service + 실제 Testcontainers PostgreSQL + {@link CountDownLatch})으로
 * "공유 자체의 변경"이 이미 진행 중인 색인 연산을 되살리는지 확인한다: unshare 후 재게시(새
 * shareId), 그리고 관리자 차단 후 해제(같은 shareId, 더 높은 generation) 둘 다 옛 연산을
 * 펜싱해야 한다 - "checking a mutable share without protecting against concurrent changes is
 * insufficient."
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class IndexOrchestratorShareRaceConcurrencyTest {

    private static final long BOUND_SECONDS = 10;

    @Autowired
    private IndexOrchestrator indexOrchestrator;
    @Autowired
    private SourceSharingService sourceSharingService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private DocumentShareJpaRepository documentShareJpaRepository;
    @Autowired
    private DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository;
    @Autowired
    private AiUsagePolicyJpaRepository aiUsagePolicyJpaRepository;

    @MockitoSpyBean
    private GoogleDriveConnector googleDriveConnector;
    @MockitoBean
    private DocumentParsingClient documentParsingClient;

    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @BeforeEach
    void ensureLocalAiUsageIsAllowedForRelevantClassifications() {
        for (String classification : new String[] {"INTERNAL", "SECRET"}) {
            if (aiUsagePolicyJpaRepository.findById(classification).isEmpty()) {
                aiUsagePolicyJpaRepository.saveAndFlush(new AiUsagePolicyEntity(classification, "LOCAL_ONLY", false));
            }
        }
    }

    @Test
    void unshareThenRepublishWhileTheOldFetchIsPausedDoesNotLetTheStaleOperationPublishUnderTheNewShare()
            throws Exception {
        Fixture fixture = createFixture("owner-unshare-republish-" + UUID.randomUUID());
        Long publishedShareId = documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(fixture.documentId())
                .orElseThrow().getId();

        CountDownLatch contentFetchStarted = new CountDownLatch(1);
        CountDownLatch shareChangeCompleted = new CountDownLatch(1);
        stubContentFetchToPauseOn(fixture, contentFetchStarted, shareChangeCompleted);

        Future<IndexProcessingOutcome> indexingFuture =
                executor.submit(() -> indexOrchestrator.process(fixture.documentId()));
        assertThat(contentFetchStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 별도의, 독립적으로 Commit되는 실제 Transaction - 옛 Fetch가 멈춰있는 동안 unshare 후
        // 완전히 새 shareId로 재게시된다(같은 문서, 다른 공유 신원).
        sourceSharingService.unshare(fixture.owner(), publishedShareId);
        sourceSharingService.createShare(fixture.owner(), fixture.sourceId(), fixture.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of("recipient-b"));
        shareChangeCompleted.countDown();

        IndexProcessingOutcome outcome = indexingFuture.get(BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(outcome)
                .as("a fetch authorized under the original share must not publish just because a coincidentally "
                        + "valid new share now exists")
                .isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        assertNoEmbeddingsPublished(fixture.documentId());
    }

    @Test
    void adminBlockThenUnblockWhileTheOldFetchIsPausedDoesNotLetTheStaleOperationPublish() throws Exception {
        Fixture fixture = createFixture("owner-block-unblock-" + UUID.randomUUID());
        Long shareId = documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(fixture.documentId())
                .orElseThrow().getId();

        CountDownLatch contentFetchStarted = new CountDownLatch(1);
        CountDownLatch shareChangeCompleted = new CountDownLatch(1);
        stubContentFetchToPauseOn(fixture, contentFetchStarted, shareChangeCompleted);

        Future<IndexProcessingOutcome> indexingFuture =
                executor.submit(() -> indexOrchestrator.process(fixture.documentId()));
        assertThat(contentFetchStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 같은 shareId지만 차단+해제로 generation이 두 번 전진한다 - 최종 상태는 다시
        // "활성+비차단"이지만, 자격 판단 시점에 캡처한 generation과는 더 이상 같지 않다.
        sourceSharingService.adminSetBlocked("admin-subject", shareId, true, "temporary policy review");
        sourceSharingService.adminSetBlocked("admin-subject", shareId, false, null);
        shareChangeCompleted.countDown();

        IndexProcessingOutcome outcome = indexingFuture.get(BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(outcome)
                .as("a block-then-unblock cycle must fence out an operation captured before it, even though the "
                        + "share ends up unblocked again")
                .isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        assertNoEmbeddingsPublished(fixture.documentId());
    }

    private void stubContentFetchToPauseOn(Fixture fixture, CountDownLatch started, CountDownLatch resumeSignal) {
        doAnswer(invocation -> {
            started.countDown();
            boolean resumedInTime = resumeSignal.await(BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(resumedInTime).as("the concurrent share change must complete within the bounded wait")
                    .isTrue();
            return SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false);
        }).when(googleDriveConnector).fetchContent(any(), eq(fixture.sourceId()), any(), eq("v1"));
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(fixture.sourceId()), any()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1",
                        Instant.now(), false));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", "bge-m3:567m", List.of(validChunk())));
    }

    private void assertNoEmbeddingsPublished(Long documentId) {
        List<DocumentEmbeddingEntity> rows = documentEmbeddingJpaRepository.findAll().stream()
                .filter(row -> row.getDocumentId().equals(documentId)).toList();
        assertThat(rows).isEmpty();
    }

    private Fixture createFixture(String owner) {
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE",
                "FULL", owner);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        long sourceId = connection.getId();
        SourceDocumentEntity document = new SourceDocumentEntity(sourceId, "ext-doc-1", "Doc.pdf", "application/pdf",
                "v1", null, "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        long documentId = document.getId();
        // SourceSharingService.createShare를 그대로 재사용한다(직접 Entity를 삽입하면 이
        // Service가 강제하는 수신자 비어있지 않음 불변식을 실수로 어길 수 있다 - adminSetBlocked가
        // 이후 toDomain에서 그 불변식을 다시 확인한다).
        sourceSharingService.createShare(owner, sourceId, documentId, "INTERNAL", Set.of("VIEW"),
                Set.of("recipient-a"));
        return new Fixture(owner, sourceId, documentId);
    }

    private static EmbeddingChunk validChunk() {
        return new EmbeddingChunk(0, LocatorType.PAGE, "1",
                java.util.Collections.nCopies(DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS, 0.01f), "a".repeat(64));
    }

    private record Fixture(String owner, Long sourceId, Long documentId) {
    }
}
