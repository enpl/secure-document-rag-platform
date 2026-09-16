package com.sdv.rag;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import com.sdv.rag.application.LiveEvidenceRetrievalService;
import com.sdv.rag.application.RagRetrievalService;
import com.sdv.rag.domain.EvidenceBatchResult;
import com.sdv.rag.domain.ExtractedLocation;
import com.sdv.rag.domain.LiveRetrievalResult;
import com.sdv.rag.domain.LiveRetrievalStatus;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.source.application.SourceSharingService;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M12 Focused Acceptance Test Group F(비보관/한도, `docs/spec/SDV_v3.2_CORE_SPEC.md`
 * §2A.2/§2A.3/§2A.6) - {@code ZeroOriginalPersistenceE2ETest}(M07A)와 같은 원칙의
 * Synthetic Canary Sweep: 인가된 Live Retrieval이 만들어낸 근거 발췌(합성 마커 포함)가
 * 이 Test가 실제로 손댄 {@code document_embedding_index}와 이번 실행 동안 Capture한
 * Application Log 어디에도 남지 않음을 확인한다. 사용자 파일시스템/임의 백업/무관한
 * 인프라는 스캔하지 않는다(정직한 경계 - 아래 Class 하단 설명 참고).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class RagLiveRetrievalNonRetentionCanaryTest {

    @Autowired
    private LiveEvidenceRetrievalService liveEvidenceRetrievalService;
    @Autowired
    private RagRetrievalService ragRetrievalService;
    @Autowired
    private SourceSharingService sourceSharingService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository;
    @Autowired
    private AiUsagePolicyJpaRepository aiUsagePolicyJpaRepository;

    @MockitoSpyBean
    private GoogleDriveConnector googleDriveConnector;
    @MockitoBean
    private DocumentParsingClient documentParsingClient;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        if (aiUsagePolicyJpaRepository.findById("INTERNAL").isEmpty()) {
            aiUsagePolicyJpaRepository.saveAndFlush(new AiUsagePolicyEntity("INTERNAL", "LOCAL_ONLY", false));
        }
        logCapture = new ListAppender<>();
        logCapture.start();
        rootLogger().addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        rootLogger().detachAppender(logCapture);
        logCapture.stop();
    }

    @Test
    void verifiedEvidenceTextNeverLeaksIntoTheEmbeddingIndexOrCapturedLogs() {
        String canary = "RAG-EVIDENCE-CANARY-" + UUID.randomUUID();
        Fixture fixture = createFixture("publisher-canary-" + UUID.randomUUID(), "recipient-canary-" + UUID.randomUUID());
        when(googleDriveConnector.verifyForAi(any(), anyLong()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1",
                        Instant.now(), true));
        when(googleDriveConnector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false));
        when(documentParsingClient.parse(any(), any(), any(), anyLong())).thenReturn(ParseOutcome.success("pdfminer.six", "1",
                "1", canary, List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, canary.length()))));

        String conversationId = ragRetrievalService.openEvidenceConversation(fixture.recipientContext());
        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(fixture.recipientContext(),
                fixture.documentId(), conversationId, null);
        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.VERIFIED);

        List<String> embeddingLocatorValues = documentEmbeddingJpaRepository.findAll().stream()
                .filter(row -> row.getDocumentId().equals(fixture.documentId()))
                .map(row -> row.getLocatorValue() + row.getLocatorType() + row.getContentHmac())
                .toList();
        assertThat(embeddingLocatorValues)
                .as("Mandatory Live Retrieval never writes into the embedding-only candidate index")
                .isEmpty();

        boolean canaryLeakedInLogs = logCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message != null)
                .anyMatch(message -> message.contains(canary));
        assertThat(canaryLeakedInLogs).as("the synthetic evidence canary must never appear in captured log output")
                .isFalse();

        String released = ragRetrievalService.releaseEvidence(fixture.recipientContext(), fixture.documentId(),
                conversationId, result.evidenceHandle()).orElseThrow();
        assertThat(released).isEqualTo(canary);
    }

    @Test
    void aRequestExceedingTheConfiguredFileCountBudgetIsHonestlyReportedAsPartial() {
        Fixture first = createFixture("publisher-budget-1-" + UUID.randomUUID(), "recipient-budget-" + UUID.randomUUID());
        String recipient = first.recipient();
        List<Long> documentIds = new ArrayList<>();
        documentIds.add(first.documentId());
        for (int i = 0; i < 6; i++) {
            // 두 번째 Fixture부터는 같은 Recipient에게 공유하는 서로 다른 문서를 추가로 만든다
            // (Default sdv.rag.live-retrieval.max-files-per-request=5보다 많은 파일 수).
            documentIds.add(createFixtureForExistingRecipient("publisher-budget-" + i + "-" + UUID.randomUUID(),
                    recipient).documentId());
        }
        when(googleDriveConnector.verifyForAi(any(), anyLong()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1",
                        Instant.now(), true));
        when(googleDriveConnector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false));
        when(documentParsingClient.parse(any(), any(), any(), anyLong())).thenReturn(ParseOutcome.success("pdfminer.six", "1",
                "1", "text", List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, 4))));

        String conversationId = ragRetrievalService.openEvidenceConversation(first.recipientContext());
        EvidenceBatchResult batch = ragRetrievalService.retrieveVerifiedEvidence(first.recipientContext(),
                conversationId, documentIds, Map.of());

        assertThat(batch.requestPartial()).as("exceeding max-files-per-request must be honestly reported").isTrue();
        assertThat(batch.results()).hasSize(5);
        verify(googleDriveConnector, times(5)).fetchForAi(any(), eq("v1"), anyLong());
    }

    /**
     * <p><b>정직한 범위(지어내지 않는다):</b> 이 Test는 이 Test 자신이 손댄 {@code
     * document_embedding_index} 행과 Capture한 Log만 검사한다 - 사용자 파일시스템,
     * 임의 Backup/Snapshot, 무관한 인프라를 스캔하지 않는다. 이 저장소 코드베이스
     * 전체(grep 확인 결과, 이 작업 시점 기준)에 Content용 임시 파일/디스크 Cache
     * Writer가 없으므로 별도 임시 경로 스윕도 수행하지 않는다({@code
     * ZeroOriginalPersistenceE2ETest}와 동일한 경계 원칙). 이 Test는 Process 종료/
     * 재시작 시 실제 물리 메모리가 소거된다는 것을 증명하지 않는다 - {@link
     * com.sdv.rag.infrastructure.ephemeral.EncryptedEphemeralEvidenceStoreTest#aRecreatedStoreCannotDecryptEntriesFromAPriorProcessInstance}
     * 가 "재시작한 새 Process는 이전 항목을 복호화할 수조차 없다"는 설계 경계만
     * 검증한다.</p>
     */
    private Fixture createFixture(String publisher, String recipient) {
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE",
                "FULL", publisher);
        connection.adoptProviderAccountId("verified-account-" + publisher);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), "ext-" + UUID.randomUUID(),
                "Doc.pdf", "application/pdf", "v1", null, "ACTIVE", "INDEXED", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        sourceSharingService.createShare(publisher, connection.getId(), document.getId(), "INTERNAL", Set.of("VIEW"),
                Set.of(recipient));
        return new Fixture(recipient, document.getId());
    }

    private Fixture createFixtureForExistingRecipient(String publisher, String recipient) {
        return createFixture(publisher, recipient);
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private record Fixture(String recipient, Long documentId) {
        UserContext recipientContext() {
            return new UserContext(recipient, recipient + "@example.com", Set.of(Role.USER), Set.of());
        }
    }
}
