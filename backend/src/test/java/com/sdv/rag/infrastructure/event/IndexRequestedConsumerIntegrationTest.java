package com.sdv.rag.infrastructure.event;

import com.sdv.rag.domain.EmbeddingChunk;
import com.sdv.rag.domain.IndexOutcome;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import com.sdv.rag.infrastructure.persistence.entity.ProcessedEventEntity;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.rag.infrastructure.persistence.repository.ProcessedEventJpaRepository;
import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M11 신규 - "Exercise the wired event-to-orchestrator path, not only disconnected
 * unit mocks"(이 작업 지시사항 7번). 격리된 실제 Testcontainers Kafka Broker + 실제
 * Testcontainers PostgreSQL 위에서, 실제 {@code @KafkaListener}({@link
 * IndexRequestedConsumer}) -> 실제 {@link com.sdv.rag.application.IndexOrchestrator}
 * -> 실제 {@link DocumentEmbeddingJpaRepository#replaceGeneration} 경로 전체를
 * 검증한다. Google Drive/Python AI Service만 Mock(둘 다 외부 Live 서비스이므로 -
 * "실제 Google/OIDC/브라우저/테스트베드 실행은 여전히 보류") - 그 사이의 모든
 * Spring/Kafka/DB 배선은 실제로 동작한다.
 *
 * <p>이 Test는 Kafka로 직접 메시지를 만들어 보낸다(Outbox Publisher를 거치지
 * 않는다) - Outbox 행이 실제로 이 Topic까지 신뢰성 있게 도달한다는 것은 이미
 * {@code OutboxEventPublisherIntegrationTest}(M09A)가 별도로 검증했다. 이 Test는
 * 그 뒤(Topic 도달 이후)의 소비/색인 경로만 정확히 겨냥한다.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.rag.index-consumer.enabled=true",
        "sdv.rag.index-consumer.topic=sdv.test.index-requested.events",
        "sdv.rag.index-consumer.group-id=sdv-test-index-orchestrator",
        "sdv.rag.index-consumer.max-attempts=2",
        "sdv.rag.index-consumer.backoff-ms=200"
})
class IndexRequestedConsumerIntegrationTest {

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerKafkaBootstrapServers(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;
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

    // Spy(Mock 아님) - SourceSyncServiceIntegrationTest와 동일한 이유(그 Class의
    // Javadoc 참고): SourceConnectorRegistry가 Application Context 기동 시점에 이미
    // supportedType()을 호출해 Map.copyOf(...)로 굳힌다(null Key 거부 -> NPE, 순수
    // Mock이었다면 stub 전까지 null을 반환해 Registry 생성 자체가 실패했을 것이다) -
    // Spy는 고정 상수를 반환하는 이 Method의 실제 구현을 그대로 쓴다. 나머지 각
    // 메서드는 doReturn(...).when(googleDriveConnector)....로 stub한다(when(spy.x())
    // 형태는 stub 전에 실제 메서드를 먼저 실행시켜 버린다).
    @MockitoSpyBean
    private GoogleDriveConnector googleDriveConnector;
    @MockitoBean
    private DocumentParsingClient documentParsingClient;

    @BeforeEach
    void ensureLocalAiUsageIsAllowedForInternalClassification() {
        // ai_usage_policies는 V001에 Seed Data가 없다(AiUsagePolicyServiceTest/
        // EffectivePermissionServiceDecisionTest와 동일한 관례) - 정책이 없으면 Fail
        // Closed(거부)이므로, 이 Test가 검증하려는 "Eligible" 경로를 위해 명시적으로
        // 이 행을 만든다. bge-m3 Embedding은 Local Provider(Ollama)이므로 LOCAL_ONLY로
        // 충분하다(External Provider 승인이 필요 없다).
        if (aiUsagePolicyJpaRepository.findById("INTERNAL").isEmpty()) {
            aiUsagePolicyJpaRepository.saveAndFlush(new AiUsagePolicyEntity("INTERNAL", "LOCAL_ONLY", false));
        }
    }

    @Test
    void publishingAnIndexRequestedEventOnTheWiredTopicResultsInANewEmbeddingGenerationAndAProcessedEventRecord() {
        long sourceId = createSource("owner-" + UUID.randomUUID());
        long documentId = createDocument(sourceId, "v1");
        createActiveShare(sourceId, documentId);
        doReturn(SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false))
                .when(googleDriveConnector).fetchContent(any(), eq(sourceId), any(), eq("v1"));
        givenSuccessfulPostParseRecheck(sourceId, "v1");
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", "bge-m3:567m", List.of(validChunk())));
        UUID eventId = UUID.randomUUID();

        sendIndexRequested(eventId, sourceId, documentId);

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            List<DocumentEmbeddingEntity> rows = documentEmbeddingJpaRepository.findAll().stream()
                    .filter(row -> row.getDocumentId().equals(documentId)).toList();
            assertThat(rows).hasSize(1);
            SourceDocumentEntity reloaded = sourceDocumentJpaRepository.findById(documentId).orElseThrow();
            assertThat(reloaded.getIndexStatus()).isEqualTo("INDEXED");
            assertThat(processedEventJpaRepository.existsById(
                    new ProcessedEventEntity.Key(eventId, IndexRequestedConsumer.CONSUMER_NAME))).isTrue();
        });
    }

    @Test
    void duplicateDeliveryOfTheSameEventIdYieldsExactlyOneEmbeddingGeneration() {
        long sourceId = createSource("owner-" + UUID.randomUUID());
        long documentId = createDocument(sourceId, "v1");
        createActiveShare(sourceId, documentId);
        doReturn(SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false))
                .when(googleDriveConnector).fetchContent(any(), eq(sourceId), any(), eq("v1"));
        givenSuccessfulPostParseRecheck(sourceId, "v1");
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", "bge-m3:567m", List.of(validChunk())));
        UUID eventId = UUID.randomUUID();

        sendIndexRequested(eventId, sourceId, documentId);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(processedEventJpaRepository.existsById(
                        new ProcessedEventEntity.Key(eventId, IndexRequestedConsumer.CONSUMER_NAME))).isTrue());

        // 같은 eventId로 다시 전달(At-Least-Once의 실제 결과) - 다시 처리되지 않아야 한다.
        sendIndexRequested(eventId, sourceId, documentId);
        await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ofSeconds(2)).untilAsserted(() -> {
            List<DocumentEmbeddingEntity> rows = documentEmbeddingJpaRepository.findAll().stream()
                    .filter(row -> row.getDocumentId().equals(documentId)).toList();
            assertThat(rows).hasSize(1); // 여전히 Generation 하나뿐 - 중복 처리로 늘어나지 않았다.
        });
        // 두 번째 전달은 documentParsingClient.index를 다시 호출하지 않는다(멱등 - 무거운
        // 재작업을 피한다) - 정확히 한 번만 호출됐어야 한다.
        org.mockito.Mockito.verify(documentParsingClient, org.mockito.Mockito.times(1)).index(any(), any(), any());
    }

    @Test
    void aCatalogSyncEventTypeOnTheSameTopicNeverTriggersAContentFetchForItsDocument() {
        long sourceId = createSource("owner-" + UUID.randomUUID());
        long ineligibleDocumentId = createDocument(sourceId, "v1"); // 공유되지 않은 문서 - 색인 대상이 아니다.
        long eligibleSourceId = createSource("owner-" + UUID.randomUUID());
        long eligibleDocumentId = createDocument(eligibleSourceId, "v1");
        createActiveShare(eligibleSourceId, eligibleDocumentId);
        doReturn(SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false))
                .when(googleDriveConnector).fetchContent(any(), eq(eligibleSourceId), any(), eq("v1"));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", "bge-m3:567m", List.of(validChunk())));

        sendCatalogSyncEvent(sourceId, ineligibleDocumentId);
        UUID eligibleEventId = UUID.randomUUID();
        sendIndexRequested(eligibleEventId, eligibleSourceId, eligibleDocumentId);

        // 이 Eligible 이벤트가 끝났다는 것은(같은 Topic/단일 Partition 순서상) 그 앞서 보낸
        // Catalog Sync 이벤트도 이미 (무시로) 처리된 뒤라는 뜻이다.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(processedEventJpaRepository.existsById(
                        new ProcessedEventEntity.Key(eligibleEventId, IndexRequestedConsumer.CONSUMER_NAME)))
                        .isTrue());

        verify(googleDriveConnector, never()).fetchContent(any(), eq(sourceId), any(), any());
        List<DocumentEmbeddingEntity> ineligibleRows = documentEmbeddingJpaRepository.findAll().stream()
                .filter(row -> row.getDocumentId().equals(ineligibleDocumentId)).toList();
        assertThat(ineligibleRows).isEmpty();
    }

    /**
     * M11 후속 교정 - {@code IndexOrchestrator}가 AI 파싱 성공 후 발행 직전 호출하는
     * Post-parse Recheck({@code verifyCurrentMetadata})를 성공으로 고정한다. 이 Test는
     * 실제 OAuth Token을 저장하지 않으므로(Google/Python만 Mock, 그 사이 배선은 실제),
     * 이 Stub이 없으면 Spy가 감싼 실제 구현이 Credential 부재로 항상 실패를 반환해
     * 발행 자체가 결코 일어나지 않는다.
     */
    private void givenSuccessfulPostParseRecheck(long sourceId, String version) {
        doReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", version, Instant.now(),
                false)).when(googleDriveConnector).verifyCurrentMetadata(any(), eq(sourceId), any());
    }

    private void sendIndexRequested(UUID eventId, long sourceId, long documentId) {
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"INDEX_REQUESTED\",\"schemaVersion\":\"1\","
                + "\"sourceId\":\"" + sourceId + "\",\"sourceAccountSubject\":\"owner\",\"internalDocumentId\":\""
                + documentId + "\",\"externalDocumentId\":\"ext-" + documentId + "\",\"sourceVersion\":\"v1\","
                + "\"occurredAt\":\"" + Instant.now() + "\",\"traceId\":\"trace-1\"}";
        kafkaTemplate.send("sdv.test.index-requested.events", "source:" + sourceId + ":doc:ext-" + documentId,
                payload);
    }

    private void sendCatalogSyncEvent(long sourceId, long documentId) {
        String payload = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"SOURCE_DOCUMENT_CHANGED\","
                + "\"schemaVersion\":\"1\",\"sourceId\":\"" + sourceId + "\",\"sourceAccountSubject\":\"owner\","
                + "\"internalDocumentId\":\"" + documentId + "\",\"externalDocumentId\":\"ext-" + documentId + "\","
                + "\"sourceVersion\":\"v1\",\"occurredAt\":\"" + Instant.now() + "\",\"traceId\":\"trace-0\"}";
        kafkaTemplate.send("sdv.test.index-requested.events", "source:" + sourceId + ":doc:ext-" + documentId,
                payload);
    }

    private long createSource(String owner) {
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE",
                "FULL", owner);
        return sourceConnectionJpaRepository.saveAndFlush(connection).getId();
    }

    private long createDocument(long sourceId, String sourceVersion) {
        SourceDocumentEntity document = new SourceDocumentEntity(sourceId, "ext-" + UUID.randomUUID(), "Doc.pdf",
                "application/pdf", sourceVersion, null, "ACTIVE", "PENDING", null);
        return sourceDocumentJpaRepository.saveAndFlush(document).getId();
    }

    private void createActiveShare(long sourceId, long documentId) {
        DocumentShareEntity share = new DocumentShareEntity("owner", sourceId, documentId, "ALL_AUTHENTICATED",
                "INTERNAL", "VIEW", Instant.now());
        documentShareJpaRepository.saveAndFlush(share);
    }

    private static EmbeddingChunk validChunk() {
        return new EmbeddingChunk(0, LocatorType.PAGE, "1",
                java.util.Collections.nCopies(DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS, 0.01f), "a".repeat(64));
    }
}
