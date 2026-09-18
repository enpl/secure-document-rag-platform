package com.sdv.rag.infrastructure.event;

import com.sdv.audit.application.AuditService;
import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import com.sdv.rag.application.IndexOrchestrator;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.entity.ProcessedEventEntity;
import com.sdv.rag.infrastructure.persistence.repository.ProcessedEventJpaRepository;
import com.sdv.source.application.SourceSharingService;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

/**
 * M17 SOURCE_ACCESS 진단 세분화 - 집중 회귀(이 작업 지시사항의 [검증] "원본 접근의
 * 각 제외 결과가 consumer를 거쳐 실제 DB reason_code까지 전달되는 집중 회귀를
 * 추가한다"). {@link IndexOrchestrator#handleUnverifiedContent}가 받는 11개
 * {@link SourceContentOutcome} 값 각각이 실제 {@code IndexRequestedConsumer.onMessage}
 * 호출을 거쳐 실제 Testcontainers PostgreSQL의 {@code processed_events.reason_code}에
 * 정확히 자신만의 고정 접미사로 도달하는지 검증한다(Mock 원장이 아니다) -
 * {@code INELIGIBLE_AT_SOURCE_ACCESS} 하나로 다시 뭉개지지 않는다는 것이 핵심
 * 확인 대상이다. Google Drive/Python AI Service만 Mock한다({@code
 * IndexRequestedConsumerIntegrationTest}와 동일한 원칙).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class SourceAccessReasonCodeConsumerTest {

    @Autowired
    private IndexOrchestrator indexOrchestrator;
    @Autowired
    private SourceSharingService sourceSharingService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
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
        // 순수 POJO Consumer를 직접 구성한다(IndexRequestedConsumerLedgerGapTest와 동일한 이유 -
        // 이 Test는 실제 Broker가 필요 없다, onMessage를 직접 호출한다).
        consumer = new IndexRequestedConsumer(indexOrchestrator, processedEventJpaRepository, auditService,
                objectMapper, transactionManager);
    }

    @ParameterizedTest
    @EnumSource(value = SourceContentOutcome.class, names = { "NOT_FOUND", "TRASHED", "DOCUMENT_CHANGED",
            "VERSION_MISMATCH", "EXPORT_LIMIT_EXCEEDED", "MISSING_CREDENTIAL", "CREDENTIAL_NOT_BOUND_TO_USER",
            "CREDENTIAL_UNREADABLE", "INSUFFICIENT_SCOPE", "ACCESS_DENIED", "ACCESS_UNKNOWN" })
    void eachUnverifiedSourceContentOutcomeReachesTheLedgerWithItsOwnFixedReasonCode(SourceContentOutcome outcome) {
        Fixture fixture = createFixture("owner-source-access-" + UUID.randomUUID());
        // "synthetic" 이유 문자열은 SourceContentResult의 진단용 필드일 뿐, 이 값이
        // 원장까지 옮겨지지 않는다는 것 자체가 이 Test가 확인하려는 것 중 하나다.
        doReturn(SourceContentResult.failed(outcome, "synthetic"))
                .when(googleDriveConnector).fetchContent(any(), eq(fixture.sourceId()), any(), eq("v1"));
        UUID eventId = UUID.randomUUID();

        consumer.onMessage(indexRequestedPayload(eventId, fixture));

        ProcessedEventEntity ledgerRow = processedEventJpaRepository
                .findById(new ProcessedEventEntity.Key(eventId, IndexRequestedConsumer.CONSUMER_NAME))
                .orElseThrow();
        assertThat(ledgerRow.getOutcome()).isEqualTo("SKIPPED_INELIGIBLE");
        assertThat(ledgerRow.getReasonCode()).isEqualTo("INELIGIBLE_AT_SOURCE_ACCESS_" + outcome.name());
        // "synthetic"이나 다른 원시 문자열이 절대 원장에 섞여 들어가지 않는다.
        assertThat(ledgerRow.getReasonCode()).doesNotContain("synthetic");
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

    private record Fixture(String owner, Long sourceId, Long documentId) {
    }
}
