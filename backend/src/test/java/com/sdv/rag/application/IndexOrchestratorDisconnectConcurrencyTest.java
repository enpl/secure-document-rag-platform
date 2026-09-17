package com.sdv.rag.application;

import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import com.sdv.rag.domain.EmbeddingChunk;
import com.sdv.rag.domain.IndexOutcome;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.source.application.SourceConnectionService;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
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
import java.util.Optional;
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
 * M11 신규 - Deferred MVP-18("M11 색인(Embedding) Writer와 disconnect의 교차 경합
 * 검증 미실시")을 해소한다. {@code SourceTokenConcurrencyTest}(M08)와 정확히 같은
 * 기법을 재사용한다 - 실제 Spring이 관리하는 Proxied Service({@link IndexOrchestrator}/
 * {@link SourceConnectionService}) + 서로 독립적으로 Commit되는 실제 Transaction을
 * 각기 다른 Thread에서 실행하고, {@link CountDownLatch}로 "정확히 이 시점"을
 * 강제한다(Sleep 없음). 이 항목은 M08 Writer끼리의 경합(MVP-06, 이미 CLOSED)과
 * 다르다 - M11 자체가 존재하지 않아 그동안 검증 대상이 없었다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class IndexOrchestratorDisconnectConcurrencyTest {

    private static final long BOUND_SECONDS = 10;

    @Autowired
    private IndexOrchestrator indexOrchestrator;
    @Autowired
    private SourceConnectionService sourceConnectionService;
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

    // Spy - IndexOrchestratorTest/IndexRequestedConsumerIntegrationTest Class Javadoc과 동일한
    // 이유(supportedType()은 Application Context 기동 시점에 실제로 호출된다).
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
    void ensureLocalAiUsageIsAllowedForInternalClassification() {
        if (aiUsagePolicyJpaRepository.findById("INTERNAL").isEmpty()) {
            aiUsagePolicyJpaRepository.saveAndFlush(new AiUsagePolicyEntity("INTERNAL", "LOCAL_ONLY", false));
        }
    }

    /**
     * "pause the content fetch after eligibility is captured, complete disconnect, release the
     * fetch, and inspect DB state from a fresh persistence context." {@link
     * IndexOrchestrator#process}는 Google 호출 도중 어떤 Lock도 쥐지 않는다(Class Javadoc) -
     * 그 대기 동안 실제 {@code disconnect()}가 별도 Transaction으로 완전히 Commit된다.
     */
    @Test
    void aContentFetchPausedMidFlightDoesNotPublishStaleEmbeddingsForASourceDisconnectedWhileItWasWaiting()
            throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE",
                "FULL", owner);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        long sourceId = connection.getId();
        SourceDocumentEntity document = new SourceDocumentEntity(sourceId, "ext-doc-1", "Doc.pdf", "application/pdf",
                "v1", null, "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        long documentId = document.getId();
        documentShareJpaRepository.saveAndFlush(
                new DocumentShareEntity(owner, sourceId, documentId, "ALL_AUTHENTICATED", "INTERNAL", "VIEW",
                        Instant.now()));

        CountDownLatch contentFetchStarted = new CountDownLatch(1);
        CountDownLatch disconnectCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            contentFetchStarted.countDown();
            boolean disconnectFinishedInTime = disconnectCommitted.await(BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(disconnectFinishedInTime).as("disconnect must complete within the bounded wait").isTrue();
            return SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false);
        }).when(googleDriveConnector).fetchContent(any(), eq(sourceId), any(), eq("v1"));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", "bge-m3:567m", List.of(validChunk())));

        Future<IndexProcessingOutcome> indexingFuture = executor.submit(() -> indexOrchestrator.process(documentId));

        assertThat(contentFetchStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        // 별도의, 독립적으로 Commit되는 실제 Transaction - Content Fetch가 멈춰있는 동안 끝까지 진행된다.
        sourceConnectionService.disconnect(sourceId, owner);
        disconnectCommitted.countDown();

        IndexProcessingOutcome outcome = indexingFuture.get(BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(outcome)
                .as("a late publication must not succeed for a source disconnected while the fetch was in flight")
                .isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);

        // 새 Persistence Context(둘 중 어느 Thread의 것도 아닌, 이 Assertion 자체의 첫 조회)로 확인한다.
        SourceConnectionEntity finalConnection = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(finalConnection.getStatus()).as("disconnect's ACTIVE->DISABLED must not be reverted")
                .isEqualTo("DISABLED");
        List<DocumentEmbeddingEntity> rows = documentEmbeddingJpaRepository.findAll().stream()
                .filter(row -> row.getDocumentId().equals(documentId)).toList();
        assertThat(rows).as("the late fetch must never publish embeddings for a disconnected source").isEmpty();
        SourceDocumentEntity reloadedDocument = sourceDocumentJpaRepository.findById(documentId).orElseThrow();
        assertThat(reloadedDocument.getIndexStatus())
                .as("a fenced-out publication must not mark the document INDEXED")
                .isNotEqualTo("INDEXED");
    }

    private static EmbeddingChunk validChunk() {
        return new EmbeddingChunk(0, LocatorType.PAGE, "1",
                java.util.Collections.nCopies(DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS, 0.01f), "a".repeat(64));
    }
}
