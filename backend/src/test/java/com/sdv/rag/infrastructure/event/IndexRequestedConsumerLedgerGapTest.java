package com.sdv.rag.infrastructure.event;

import com.sdv.audit.application.AuditService;
import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import com.sdv.rag.application.IndexOrchestrator;
import com.sdv.rag.application.IndexProcessingOutcome;
import com.sdv.rag.domain.EmbeddingChunk;
import com.sdv.rag.domain.IndexOutcome;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import com.sdv.rag.infrastructure.persistence.entity.ProcessedEventEntity;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.rag.infrastructure.persistence.repository.ProcessedEventJpaRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * M11 후속 교정 검증(2026-09-16, 이번 작업 지시사항 C) - "Address the gap between
 * embedding publication and completion-ledger persistence." 실제 Testcontainers
 * PostgreSQL로, {@link IndexOrchestrator#process}(발행)와 {@link
 * IndexRequestedConsumer#onMessage}의 완료 원장 기록({@code recordProcessed}) 사이에
 * Crash가 나서 원장이 남지 않은 채 재전달(Redelivery)되는 상황을 재현한다 - "Safe fresh
 * reprocessing is acceptable; do not claim exactly-once execution."
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class IndexRequestedConsumerLedgerGapTest {

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
    private ProcessedEventJpaRepository processedEventJpaRepository;
    @Autowired
    private AiUsagePolicyJpaRepository aiUsagePolicyJpaRepository;
    @Autowired
    private AuditService auditService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private GoogleDriveConnector googleDriveConnector;
    @MockitoBean
    private DocumentParsingClient documentParsingClient;

    private IndexRequestedConsumer consumer;

    @BeforeEach
    void setUp() {
        if (aiUsagePolicyJpaRepository.findById("INTERNAL").isEmpty()) {
            aiUsagePolicyJpaRepository.saveAndFlush(new AiUsagePolicyEntity("INTERNAL", "LOCAL_ONLY", false));
        }
        // Kafka/@ConditionalOnProperty를 우회해 실제 협력 Bean(Orchestrator/Ledger Repository)으로
        // 순수 POJO Consumer를 직접 구성한다 - 이 Test는 실제 Broker가 필요 없다(onMessage를
        // 직접 호출해 "재전달"을 흉내낸다).
        consumer = new IndexRequestedConsumer(indexOrchestrator, processedEventJpaRepository, auditService,
                objectMapper, transactionManager);
    }

    @Test
    void redeliveryAfterACrashBetweenPublicationAndLedgerPersistenceSafelyReprocessesAndCompletesTheLedger() {
        Fixture fixture = createFixture("owner-ledger-gap-" + UUID.randomUUID());
        stubSuccessfulFetchParseAndRecheck(fixture);

        // 1) "Crash 이전" - Orchestrator가 직접(Consumer를 거치지 않고) 성공적으로 발행한다.
        //    이 방식으로 "발행은 끝났지만 원장 기록은 전혀 없다"는 정확한 간극을 만든다.
        IndexProcessingOutcome firstAttemptOutcome = indexOrchestrator.process(fixture.documentId());
        assertThat(firstAttemptOutcome).isEqualTo(IndexProcessingOutcome.INDEXED);
        assertThat(documentEmbeddingJpaRepository.findAll().stream()
                .anyMatch(row -> row.getDocumentId().equals(fixture.documentId()))).isTrue();
        assertThat(processedEventJpaRepository.findAll().stream()
                .noneMatch(row -> fixture.documentId().equals(row.getDocumentId())))
                .as("the crash happened before any ledger row for this document was written")
                .isTrue();

        // 2) "재전달" - 실제 Consumer.onMessage를 통해 같은 문서에 대한 새 Kafka 전달을 흉내낸다.
        UUID redeliveryEventId = UUID.randomUUID();
        consumer.onMessage(indexRequestedPayload(redeliveryEventId, fixture));

        List<DocumentEmbeddingEntity> rows = documentEmbeddingJpaRepository.findAll().stream()
                .filter(row -> row.getDocumentId().equals(fixture.documentId())).toList();
        assertThat(rows).as("safe fresh reprocessing - a fresh generation is published again, not corrupted")
                .isNotEmpty();
        SourceDocumentEntity reloaded = sourceDocumentJpaRepository.findById(fixture.documentId()).orElseThrow();
        assertThat(reloaded.getIndexStatus()).isEqualTo("INDEXED");
        assertThat(processedEventJpaRepository.existsById(
                new ProcessedEventEntity.Key(redeliveryEventId, IndexRequestedConsumer.CONSUMER_NAME)))
                .as("the redelivery must complete the ledger entry that the crash skipped")
                .isTrue();
    }

    @Test
    void redeliveryAfterTheCrashCannotReviveWorkRevokedInTheMeantime() {
        Fixture fixture = createFixture("owner-ledger-gap-revoked-" + UUID.randomUUID());
        stubSuccessfulFetchParseAndRecheck(fixture);

        IndexProcessingOutcome firstAttemptOutcome = indexOrchestrator.process(fixture.documentId());
        assertThat(firstAttemptOutcome).isEqualTo(IndexProcessingOutcome.INDEXED);

        // 그 사이(Crash와 재전달 사이) 게시자가 공유를 철회한다 - 실제 Embedding도 즉시 지워진다.
        Long shareId = documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(fixture.documentId())
                .orElseThrow().getId();
        sourceSharingService.unshare(fixture.owner(), shareId);
        assertThat(documentEmbeddingJpaRepository.findAll().stream()
                .noneMatch(row -> row.getDocumentId().equals(fixture.documentId())))
                .as("unshare must have already retired the embeddings")
                .isTrue();

        UUID redeliveryEventId = UUID.randomUUID();
        consumer.onMessage(indexRequestedPayload(redeliveryEventId, fixture));

        assertThat(documentEmbeddingJpaRepository.findAll().stream()
                .noneMatch(row -> row.getDocumentId().equals(fixture.documentId())))
                .as("the redelivery must not revive embeddings for now-revoked work")
                .isTrue();
        ProcessedEventEntity ledgerRow = processedEventJpaRepository
                .findById(new ProcessedEventEntity.Key(redeliveryEventId, IndexRequestedConsumer.CONSUMER_NAME))
                .orElseThrow();
        assertThat(ledgerRow.getOutcome()).isEqualTo("SKIPPED_INELIGIBLE");
    }

    private void stubSuccessfulFetchParseAndRecheck(Fixture fixture) {
        when(googleDriveConnector.fetchContent(any(), eq(fixture.sourceId()), any(), eq("v1")))
                .thenReturn(SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false));
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(fixture.sourceId()), any()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1",
                        Instant.now(), false));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", "bge-m3:567m", List.of(validChunk())));
    }

    private String indexRequestedPayload(UUID eventId, Fixture fixture) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"INDEX_REQUESTED\",\"schemaVersion\":\"1\","
                + "\"sourceId\":\"" + fixture.sourceId() + "\",\"sourceAccountSubject\":\"" + fixture.owner()
                + "\",\"internalDocumentId\":\"" + fixture.documentId() + "\",\"externalDocumentId\":\"ext-doc-1\","
                + "\"sourceVersion\":\"v1\",\"occurredAt\":\"" + Instant.now() + "\",\"traceId\":\"trace-1\"}";
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
