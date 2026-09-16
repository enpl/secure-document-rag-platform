package com.sdv.rag.application;

import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import com.sdv.rag.domain.IndexOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.source.application.SourceConnectionService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * M11 후속 교정 검증(2026-09-16, 이번 작업 지시사항 A, "Make non-success state writes
 * transactional and generation-safe") - 실제 Proxied {@link IndexOrchestrator} +
 * 실제 Testcontainers PostgreSQL로, 이 Test Class 자신은 {@code @Transactional}을
 * 전혀 쓰지 않는다("Call the real proxied orchestrator outside a test-managed
 * transaction" - Test 스스로 Transaction을 열어 두면 Production 코드의 Transaction
 * 누락을 가려버린다).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class IndexOrchestratorNonSuccessFencingConcurrencyTest {

    private static final long BOUND_SECONDS = 10;

    @Autowired
    private IndexOrchestrator indexOrchestrator;
    @Autowired
    private SourceConnectionService sourceConnectionService;
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
    @Autowired
    private PlatformTransactionManager transactionManager;

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

    /** 인수 기준 - "Eligible unsupported MIME persists SKIPPED_UNSUPPORTED without a content fetch." */
    @Test
    void eligibleUnsupportedMimePersistsSkippedUnsupportedWithoutFetchingContentAndWithoutAnAmbientTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("this test itself must not be running inside a transaction").isFalse();
        Fixture fixture = createFixture("owner-unsupported-" + UUID.randomUUID(), "image/png");

        IndexProcessingOutcome outcome = indexOrchestrator.process(fixture.documentId());

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.SKIPPED_UNSUPPORTED);
        assertThat(reloadDocument(fixture).getIndexStatus()).isEqualTo("SKIPPED_UNSUPPORTED");
        org.mockito.Mockito.verify(googleDriveConnector, org.mockito.Mockito.never())
                .fetchContent(any(), any(), any(), any());
    }

    /** 인수 기준 - "Parser NO_TEXT ... persist the correct status without transaction errors." */
    @Test
    void parserNoTextPersistsCorrectStatusWithoutTransactionErrorsOrAnAmbientTransaction() {
        Fixture fixture = createFixture("owner-no-text-" + UUID.randomUUID(), "application/pdf");
        AtomicBoolean transactionActiveDuringFetch = new AtomicBoolean(true);
        AtomicBoolean transactionActiveDuringParse = new AtomicBoolean(true);
        doAnswer(invocation -> {
            transactionActiveDuringFetch.set(TransactionSynchronizationManager.isActualTransactionActive());
            return SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false);
        }).when(googleDriveConnector).fetchContent(any(), eq(fixture.sourceId()), any(), eq("v1"));
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(fixture.sourceId()), any()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1",
                        Instant.now(), false));
        when(documentParsingClient.index(any(), any(), any())).thenAnswer(invocation -> {
            transactionActiveDuringParse.set(TransactionSynchronizationManager.isActualTransactionActive());
            return IndexOutcome.failure(ParseOutcomeKind.NO_TEXT, "no extractable text");
        });

        IndexProcessingOutcome outcome = indexOrchestrator.process(fixture.documentId());

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.SKIPPED_NO_TEXT);
        assertThat(reloadDocument(fixture).getIndexStatus()).isEqualTo("SKIPPED_NO_TEXT");
        assertThat(transactionActiveDuringFetch)
                .as("Google fetch must never run with an active DB transaction/lock").isFalse();
        assertThat(transactionActiveDuringParse)
                .as("the AI parse call must never run with an active DB transaction/lock").isFalse();
    }

    /** 인수 기준 - "An obsolete non-success result cannot alter newer document state after a connection ... change." */
    @Test
    void obsoleteNoTextResultIsFencedOutAfterAConnectionEpochChangeAndDoesNotAlterDocumentState() throws Exception {
        Fixture fixture = createFixture("owner-epoch-race-" + UUID.randomUUID(), "application/pdf");
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch disconnectCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            fetchStarted.countDown();
            assertThat(disconnectCommitted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
            return SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false);
        }).when(googleDriveConnector).fetchContent(any(), eq(fixture.sourceId()), any(), eq("v1"));
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(fixture.sourceId()), any()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1",
                        Instant.now(), false));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.failure(ParseOutcomeKind.NO_TEXT, "no extractable text"));

        Future<IndexProcessingOutcome> future = executor.submit(() -> indexOrchestrator.process(fixture.documentId()));
        assertThat(fetchStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        // 별도의, 독립적으로 Commit되는 실제 Transaction - Fetch가 멈춰있는 동안 Disconnect가
        // connection_epoch를 올린다.
        sourceConnectionService.disconnect(fixture.sourceId(), fixture.owner());
        disconnectCommitted.countDown();

        IndexProcessingOutcome outcome = future.get(BOUND_SECONDS, TimeUnit.SECONDS);

        assertThat(outcome)
                .as("a non-success result computed before the disconnect must be fenced out, not persisted")
                .isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        assertThat(reloadDocument(fixture).getIndexStatus())
                .as("the document's index_status must remain unchanged - the stale NO_TEXT verdict must never land")
                .isEqualTo("PENDING");
    }

    /** 인수 기준 - "... or share-generation change." */
    @Test
    void obsoleteUnsupportedFormatResultIsFencedOutAfterAShareGenerationChangeAndDoesNotAlterDocumentState()
            throws Exception {
        Fixture fixture = createFixture("owner-share-race-" + UUID.randomUUID(), "application/pdf");
        Long shareId = documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(fixture.documentId())
                .orElseThrow().getId();
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch shareChanged = new CountDownLatch(1);
        doAnswer(invocation -> {
            fetchStarted.countDown();
            assertThat(shareChanged.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
            return SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false);
        }).when(googleDriveConnector).fetchContent(any(), eq(fixture.sourceId()), any(), eq("v1"));
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(fixture.sourceId()), any()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1",
                        Instant.now(), false));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.failure(ParseOutcomeKind.UNSUPPORTED_FORMAT, "unsupported by parser"));

        Future<IndexProcessingOutcome> future = executor.submit(() -> indexOrchestrator.process(fixture.documentId()));
        assertThat(fetchStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        // 같은 shareId지만 관리자 차단+해제로 generation이 두 번 전진한다.
        sourceSharingService.adminSetBlocked("admin-subject", shareId, true, "temporary review");
        sourceSharingService.adminSetBlocked("admin-subject", shareId, false, null);
        shareChanged.countDown();

        IndexProcessingOutcome outcome = future.get(BOUND_SECONDS, TimeUnit.SECONDS);

        assertThat(outcome)
                .as("a non-success result computed before the share generation changed must be fenced out")
                .isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        assertThat(reloadDocument(fixture).getIndexStatus())
                .as("the document's index_status must remain unchanged")
                .isEqualTo("PENDING");
    }

    /** 인수 기준 - "Existing newer INDEXED protection remains intact." */
    @Test
    void aNonSuccessResultNeverDowngradesADocumentThatIsAlreadyIndexed() {
        Fixture fixture = createFixture("owner-already-indexed-" + UUID.randomUUID(), "application/pdf");
        // 이미 다른(더 새로운) 시도가 성공적으로 색인을 마쳤다고 직접 반영한다 - 이 Setup
        // 호출 자체는 이 Test의 자체 짧은 Transaction 안에서 실행한다(@Modifying Query는
        // Transaction 없이 호출하면 TransactionRequiredException을 던진다 - 정확히 이번
        // 교정이 Production 코드에서 고친 문제와 같은 종류다). 아래 실제 검증 대상인
        // indexOrchestrator.process() 호출은 이 Transaction 밖에서 그대로 실행된다.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                sourceDocumentJpaRepository.updateIndexStatus(fixture.documentId(), "INDEXED", null));
        when(googleDriveConnector.fetchContent(any(), eq(fixture.sourceId()), any(), eq("v1")))
                .thenReturn(SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false));
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(fixture.sourceId()), any()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1",
                        Instant.now(), false));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.failure(ParseOutcomeKind.NO_TEXT, "no extractable text"));

        IndexProcessingOutcome outcome = indexOrchestrator.process(fixture.documentId());

        assertThat(outcome)
                .as("fenced out because the document is already at a newer (INDEXED) generation")
                .isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        assertThat(reloadDocument(fixture).getIndexStatus())
                .as("an obsolete NO_TEXT verdict must never downgrade an already-INDEXED document")
                .isEqualTo("INDEXED");
    }

    private SourceDocumentEntity reloadDocument(Fixture fixture) {
        return sourceDocumentJpaRepository.findById(fixture.documentId()).orElseThrow();
    }

    private Fixture createFixture(String owner, String mimeType) {
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE",
                "FULL", owner);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        long sourceId = connection.getId();
        SourceDocumentEntity document = new SourceDocumentEntity(sourceId, "ext-doc-1", "Doc", mimeType, "v1", null,
                "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        long documentId = document.getId();
        // SourceSharingService.createShare를 재사용한다 - 직접 Entity를 삽입하면 이 Service가
        // 강제하는 수신자 비어있지 않음 불변식을 실수로 어길 수 있다(adminSetBlocked가 이후
        // toDomain에서 그 불변식을 다시 확인한다).
        sourceSharingService.createShare(owner, sourceId, documentId, "INTERNAL", Set.of("VIEW"),
                Set.of("recipient-a"));
        return new Fixture(owner, sourceId, documentId);
    }

    private record Fixture(String owner, Long sourceId, Long documentId) {
    }
}
