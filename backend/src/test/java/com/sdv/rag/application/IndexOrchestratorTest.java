package com.sdv.rag.application;

import com.sdv.common.model.UserContext;
import com.sdv.policy.application.AiUsagePolicyService;
import com.sdv.policy.domain.AiRequestContext;
import com.sdv.policy.domain.PolicyDecision;
import com.sdv.policy.domain.PolicyReasonCode;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.rag.domain.EmbeddingChunk;
import com.sdv.rag.domain.IndexOutcome;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.source.application.SourceConnectorRegistry;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceMetadataVerificationOutcome;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M11 신규 - 순수 단위 테스트(Mockito, Spring Context/실제 DB 없음)로
 * {@link IndexOrchestrator}의 자격 판단/Pre-classification/실패 분류/발행 직전
 * 재검증 분기를 검증한다. {@link PlatformTransactionManager}는 Mockito 기본
 * Mock(모든 메서드가 no-op)으로 충분하다 - {@link
 * org.springframework.transaction.support.TransactionTemplate#execute}가 그
 * 위에서 그대로 동기적으로 Callback을 실행하므로, 실제 DB Transaction 없이도
 * "발행 직전 재검증" 분기의 순수 결정 로직을 검증할 수 있다. 원자적 Generation
 * 교체 자체(실제 pgvector Insert/Lock)의 검증은 {@code
 * DocumentEmbeddingJpaRepositoryTest}(M07A)와 아래
 * {@code IndexRequestedConsumerIntegrationTest}/{@code
 * IndexOrchestratorDisconnectConcurrencyTest}(둘 다 실제 Testcontainers) 몫이다.
 */
@ExtendWith(MockitoExtension.class)
class IndexOrchestratorTest {

    private static final Long DOCUMENT_ID = 42L;
    private static final Long SOURCE_ID = 7L;
    private static final Long SHARE_ID = 100L;
    private static final String SOURCE_VERSION = "v1";
    private static final String OWNER = "owner-subject";

    @Mock
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Mock
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Mock
    private DocumentShareJpaRepository documentShareJpaRepository;
    @Mock
    private DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository;
    @Mock
    private AiUsagePolicyService aiUsagePolicyService;
    @Mock
    private DocumentParsingClient documentParsingClient;
    @Mock
    private DocumentSourceConnector googleDriveConnector;
    @Mock
    private PlatformTransactionManager transactionManager;

    private IndexOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        when(googleDriveConnector.supportedType()).thenReturn(SourceType.GOOGLE_DRIVE);
        SourceConnectorRegistry registry = new SourceConnectorRegistry(List.of(googleDriveConnector));
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
        orchestrator = new IndexOrchestrator(sourceDocumentJpaRepository, sourceConnectionJpaRepository,
                documentShareJpaRepository, documentEmbeddingJpaRepository, registry, aiUsagePolicyService,
                documentParsingClient, transactionManager, fixedClock);
    }

    @Test
    void processReturnsSkippedIneligibleWhenNoActiveShareExists() {
        givenActiveDocument("text/plain");
        when(documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(DOCUMENT_ID)).thenReturn(Optional.empty());

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        verify(googleDriveConnector, never()).fetchContent(any(), any(), any(), any());
        verifyNoInteractions(documentParsingClient);
    }

    @Test
    void processReturnsSkippedIneligibleWhenTheActiveShareIsAdminBlocked() {
        givenActiveDocument("text/plain");
        DocumentShareEntity blockedShare = activeShare(SecurityLevel.INTERNAL);
        blockedShare.applyAdminBlock(true, "policy violation", Instant.now());
        when(documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(DOCUMENT_ID))
                .thenReturn(Optional.of(blockedShare));

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        verify(googleDriveConnector, never()).fetchContent(any(), any(), any(), any());
        verifyNoInteractions(documentParsingClient);
    }

    @Test
    void processRejectsAMalformedNamedShareWithoutRecipientsBeforeFetchingContent() {
        givenActiveDocument("application/pdf");
        DocumentShareEntity malformed = new DocumentShareEntity(OWNER, SOURCE_ID, DOCUMENT_ID,
                "NAMED_USERS", SecurityLevel.INTERNAL.name(), "VIEW", Instant.now());
        ReflectionTestUtils.setField(malformed, "id", SHARE_ID);
        when(documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(DOCUMENT_ID))
                .thenReturn(Optional.of(malformed));
        when(documentShareJpaRepository.countRecipients(SHARE_ID)).thenReturn(0L);

        assertThat(orchestrator.process(DOCUMENT_ID)).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        verify(googleDriveConnector, never()).fetchContent(any(), any(), any(), any());
        verifyNoInteractions(documentParsingClient);
    }

    @Test
    void processReturnsSkippedIneligibleWhenAiUsagePolicyDeniesTheClassification() {
        givenActiveDocument("text/plain");
        when(documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(DOCUMENT_ID))
                .thenReturn(Optional.of(activeShare(SecurityLevel.SECRET)));
        when(sourceConnectionJpaRepository.findById(SOURCE_ID)).thenReturn(Optional.of(activeConnection()));
        when(aiUsagePolicyService.evaluate(eq(SecurityLevel.SECRET), any(AiRequestContext.class)))
                .thenReturn(PolicyDecision.deny(PolicyReasonCode.AI_USAGE_DENIED));

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        verify(googleDriveConnector, never()).fetchContent(any(), any(), any(), any());
        verifyNoInteractions(documentParsingClient);
    }

    @Test
    void processSkipsAKnownUnsupportedMimeTypeWithoutEverCallingTheConnector() {
        givenActiveDocument("image/png");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        // M11 후속 교정(이번 작업 지시사항 A) - 비성공 기록도 이제 발행 직전과 같은 전체
        // Lock/재검증 체인(finalizeNonSuccess/lockAndRevalidate)을 거친다 - 이 Test도
        // "everythingIsFreshAndValid" Test와 동일하게 그 Lock 재조회 세 Method를 stub해야
        // 실제로 기록이 적용되는 경로를 관찰할 수 있다.
        when(sourceConnectionJpaRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(activeConnection()));
        when(sourceDocumentJpaRepository.findByIdForUpdate(DOCUMENT_ID))
                .thenReturn(Optional.of(activeDocument("image/png")));
        when(documentShareJpaRepository.findByIdForUpdate(SHARE_ID))
                .thenReturn(Optional.of(activeShare(SecurityLevel.INTERNAL)));
        // updateIndexStatusIfCurrent는 int(갱신된 행 수)를 반환한다 - unstubbed Mock의 기본값
        // 0은 "펜싱으로 무시됨"과 구분이 안 되므로, 실제로 적용됐음을 명시적으로 stub한다.
        when(sourceDocumentJpaRepository.updateIndexStatusIfCurrent(any(), any(), any(), any())).thenReturn(1);

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.SKIPPED_UNSUPPORTED);
        verify(googleDriveConnector, never()).fetchContent(any(), any(), any(), any());
        verifyNoInteractions(documentParsingClient);
        verify(sourceDocumentJpaRepository).updateIndexStatusIfCurrent(eq(DOCUMENT_ID), eq("SKIPPED_UNSUPPORTED"),
                any(), eq(SOURCE_VERSION));
    }

    /**
     * M11 후속 교정(이번 작업 지시사항 A) - 비성공 기록도 연결/공유 펜싱을 통과하지 못하면
     * 조용히 무시되고 전체 결과가 {@code SKIPPED_INELIGIBLE}로 대체된다(발행 경로와 동일한
     * 원칙, "Existing newer INDEXED protection remains intact"의 비성공 버전).
     */
    @Test
    void processFencesOutAnUnsupportedMimeWriteWhenTheConnectionEpochChangedBeforeFinalization() {
        givenActiveDocument("image/png");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        SourceConnectionEntity advancedEpochConnection = activeConnection();
        advancedEpochConnection.bumpConnectionEpoch();
        when(sourceConnectionJpaRepository.findByIdForUpdate(SOURCE_ID))
                .thenReturn(Optional.of(advancedEpochConnection));

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        verify(sourceDocumentJpaRepository, never()).updateIndexStatusIfCurrent(any(), any(), any(), any());
    }

    @Test
    void processMapsATrashedContentOutcomeToSkippedIneligibleWithoutThrowing() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.failed(SourceContentOutcome.TRASHED, "trashed"));

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
    }

    @Test
    void processThrowsATransientExceptionWhenContentFetchFailsGenerically() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.failed(SourceContentOutcome.FAILED, "network error"));

        assertThatThrownBy(() -> orchestrator.process(DOCUMENT_ID)).isInstanceOf(TransientIndexingException.class);
    }

    @Test
    void processThrowsATransientExceptionWhenTheEmbeddingResponseHasTheWrongDimensions() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        byte[] bytes = "pdf bytes".getBytes();
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.verified(bytes, "application/pdf", SOURCE_VERSION, false));
        givenSuccessfulPostParseRecheck(SOURCE_VERSION);
        EmbeddingChunk malformedChunk = new EmbeddingChunk(0, LocatorType.PAGE, "1", List.of(1.0f, 2.0f), // 2차원뿐 - 1024 아님
                "a".repeat(64));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("1", "1", IndexOrchestrator.EXPECTED_EMBEDDING_MODEL,
                        List.of(malformedChunk)));

        assertThatThrownBy(() -> orchestrator.process(DOCUMENT_ID)).isInstanceOf(TransientIndexingException.class);
        verify(documentEmbeddingJpaRepository, never()).replaceGeneration(anyLong(), any());
    }

    @Test
    void processPublishesIndexedWhenEverythingIsFreshAndValidAtPublicationTime() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        byte[] bytes = "pdf bytes".getBytes();
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.verified(bytes, "application/pdf", SOURCE_VERSION, false));
        givenSuccessfulPostParseRecheck(SOURCE_VERSION);
        EmbeddingChunk chunk = validChunk(0);
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", IndexOrchestrator.EXPECTED_EMBEDDING_MODEL,
                        List.of(chunk)));
        // 발행 직전 재검증 - findByIdForUpdate가 다시 호출된다(같은 연결/문서/공유를 다시 잠근다).
        when(sourceConnectionJpaRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(activeConnection()));
        when(sourceDocumentJpaRepository.findByIdForUpdate(DOCUMENT_ID))
                .thenReturn(Optional.of(activeDocument("application/pdf")));
        when(documentShareJpaRepository.findByIdForUpdate(SHARE_ID))
                .thenReturn(Optional.of(activeShare(SecurityLevel.INTERNAL)));

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.INDEXED);
        verify(documentEmbeddingJpaRepository).replaceGeneration(eq(DOCUMENT_ID), any());
        verify(sourceDocumentJpaRepository).updateIndexStatus(DOCUMENT_ID, "INDEXED", null);
    }

    @Test
    void processDoesNotPublishWhenTheSourceVersionChangedBetweenFetchAndPublication() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        byte[] bytes = "pdf bytes".getBytes();
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.verified(bytes, "application/pdf", SOURCE_VERSION, false));
        givenSuccessfulPostParseRecheck(SOURCE_VERSION);
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", IndexOrchestrator.EXPECTED_EMBEDDING_MODEL,
                        List.of(validChunk(0))));
        when(sourceConnectionJpaRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(activeConnection()));
        // 발행 직전 재확인 - 그 사이 다른 Sync가 새 Version을 이미 반영했다("newer generation").
        SourceDocumentEntity newerVersionDocument = activeDocument("application/pdf");
        setSourceVersion(newerVersionDocument, "v2-newer");
        when(sourceDocumentJpaRepository.findByIdForUpdate(DOCUMENT_ID)).thenReturn(Optional.of(newerVersionDocument));

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        verify(documentEmbeddingJpaRepository, never()).replaceGeneration(anyLong(), any());
        verify(sourceDocumentJpaRepository, never()).updateIndexStatus(eq(DOCUMENT_ID), eq("INDEXED"), any());
    }

    @Test
    void processDoesNotPublishWhenTheConnectionEpochAdvancedBetweenFetchAndPublication() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        byte[] bytes = "pdf bytes".getBytes();
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.verified(bytes, "application/pdf", SOURCE_VERSION, false));
        givenSuccessfulPostParseRecheck(SOURCE_VERSION);
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", IndexOrchestrator.EXPECTED_EMBEDDING_MODEL,
                        List.of(validChunk(0))));
        // 그 사이 Disconnect(뒤이은 재연결 포함)가 있었다 - Epoch가 자격 판단 시점(1)과 달라졌다.
        // 이 확인이 Lock 순서상 가장 먼저이므로, 이 Transaction은 source_documents/
        // document_shares를 절대 조회하지 않고 즉시 실패한다.
        SourceConnectionEntity advancedEpochConnection = activeConnection();
        advancedEpochConnection.bumpConnectionEpoch();
        when(sourceConnectionJpaRepository.findByIdForUpdate(SOURCE_ID))
                .thenReturn(Optional.of(advancedEpochConnection));

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome)
                .as("an intervening disconnect (even followed by a reconnect back to ACTIVE) must fence out the "
                        + "stale operation")
                .isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        verify(documentEmbeddingJpaRepository, never()).replaceGeneration(anyLong(), any());
    }

    @Test
    void processDoesNotPublishWhenTheShareWasModifiedBetweenFetchAndPublication() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        byte[] bytes = "pdf bytes".getBytes();
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.verified(bytes, "application/pdf", SOURCE_VERSION, false));
        givenSuccessfulPostParseRecheck(SOURCE_VERSION);
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", IndexOrchestrator.EXPECTED_EMBEDDING_MODEL,
                        List.of(validChunk(0))));
        when(sourceConnectionJpaRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(activeConnection()));
        when(sourceDocumentJpaRepository.findByIdForUpdate(DOCUMENT_ID))
                .thenReturn(Optional.of(activeDocument("application/pdf")));
        // 같은 shareId지만 그 사이 updateShare가 등급/행위를 바꿔 generation이 전진했다(2).
        DocumentShareEntity modifiedShare = activeShare(SecurityLevel.SECRET);
        modifiedShare.applyOwnerUpdate(SecurityLevel.SECRET.name(), "VIEW", Instant.now());
        ReflectionTestUtils.setField(modifiedShare, "generation", 2L);
        when(documentShareJpaRepository.findByIdForUpdate(SHARE_ID)).thenReturn(Optional.of(modifiedShare));

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome)
                .as("a share modified after eligibility was captured must fence out the stale operation, even "
                        + "though a currently-active share still exists")
                .isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        verify(documentEmbeddingJpaRepository, never()).replaceGeneration(anyLong(), any());
    }

    @Test
    void processDoesNotPublishWhenProviderAccessOrVersionChangedDuringEmbedding() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        byte[] bytes = "pdf bytes".getBytes();
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.verified(bytes, "application/pdf", SOURCE_VERSION, false));
        // Parsing 도중 Provider Version이 바뀌었다 - Post-parse Recheck가 이를 관측한다.
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(SOURCE_ID), any()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v2-changed",
                        Instant.now(), false));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", IndexOrchestrator.EXPECTED_EMBEDDING_MODEL,
                        List.of(validChunk(0))));

        IndexProcessingOutcome outcome = orchestrator.process(DOCUMENT_ID);

        assertThat(outcome).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        verify(sourceConnectionJpaRepository, never()).findByIdForUpdate(any());
        verify(documentEmbeddingJpaRepository, never()).replaceGeneration(anyLong(), any());
    }

    @Test
    void processDoesNotPublishWhenThePostParseRecheckItselfFails() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        byte[] bytes = "pdf bytes".getBytes();
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.verified(bytes, "application/pdf", SOURCE_VERSION, false));
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(SOURCE_ID), any()))
                .thenThrow(new RuntimeException("network timeout"));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", IndexOrchestrator.EXPECTED_EMBEDDING_MODEL,
                        List.of(validChunk(0))));

        assertThatThrownBy(() -> orchestrator.process(DOCUMENT_ID)).isInstanceOf(TransientIndexingException.class);
        verify(documentEmbeddingJpaRepository, never()).replaceGeneration(anyLong(), any());
    }

    /**
     * M17 진단 교정 - {@code SKIPPED_INELIGIBLE}이 자격 검사/자격증명·원본 읽기/버전
     * 검증/최종 세대 검증 중 정확히 어느 단계에서 나왔는지 {@link
     * IndexProcessingResult#reasonCode()}로 구분되는지, 그리고 그 값이 항상 이 4개
     * 고정 문자열 중 하나뿐(원시 예외/Google 응답/파일명/토큰/원문이 전혀 섞이지
     * 않음)인지 검증한다. 각 Test는 이미 위에 있는 대응 시나리오의 자격/Mock
     * 설정을 그대로 재사용한다.
     */
    @Test
    void skippedIneligibleAtTheEligibilityCheckStageCarriesThatStagesFixedReasonCode() {
        givenActiveDocument("text/plain");
        when(documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(DOCUMENT_ID)).thenReturn(Optional.empty());

        IndexProcessingResult result = orchestrator.processWithReasonCode(DOCUMENT_ID);

        assertThat(result.outcome()).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        assertThat(result.reasonCode()).isEqualTo("INELIGIBLE_AT_ELIGIBILITY_CHECK");
    }

    /**
     * M17 SOURCE_ACCESS 진단 세분화(2차 교정) - 이 11개 {@link SourceContentOutcome}
     * 값 각각이 더 이상 {@code INELIGIBLE_AT_SOURCE_ACCESS} 하나로 뭉개지지 않고,
     * 그 typed 원인 그대로 고정 접미사를 얻는지 확인한다(원시 reason 문자열
     * "synthetic"은 절대 반영되지 않는다). {@code IndexRequestedConsumer}를 거쳐
     * 실제 DB {@code reason_code}까지 도달하는지는 {@code
     * SourceAccessReasonCodeConsumerTest}(Testcontainers)가 별도로 확인한다 - 이
     * Test는 순수 단위 Test로 {@link IndexOrchestrator} 자신의 판정만 좁게 본다.
     */
    @ParameterizedTest
    @EnumSource(value = SourceContentOutcome.class, names = { "NOT_FOUND",
            "TRASHED", "DOCUMENT_CHANGED", "VERSION_MISMATCH", "EXPORT_LIMIT_EXCEEDED", "MISSING_CREDENTIAL",
            "CREDENTIAL_NOT_BOUND_TO_USER", "CREDENTIAL_UNREADABLE", "INSUFFICIENT_SCOPE", "ACCESS_DENIED",
            "ACCESS_UNKNOWN" })
    void skippedIneligibleAtTheSourceAccessStageCarriesTheTypedOutcomesOwnFixedReasonCode(
            SourceContentOutcome outcome) {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.failed(outcome, "synthetic"));

        IndexProcessingResult result = orchestrator.processWithReasonCode(DOCUMENT_ID);

        assertThat(result.outcome()).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        assertThat(result.reasonCode()).isEqualTo("INELIGIBLE_AT_SOURCE_ACCESS_" + outcome.name());
    }

    /**
     * M17 진단 세분화 - Connector가 아예 등록돼 있지 않은 경우도(알 수 없는 Source
     * Type이 아니라 "알려진 Type이지만 등록된 Connector가 없음") SOURCE_ACCESS
     * 단계 중 자신만의 고정 코드를 얻는다 - Google 호출 자체가 시도되지 않는다.
     */
    @Test
    void skippedIneligibleWhenNoConnectorIsRegisteredForTheKnownSourceTypeCarriesItsOwnFixedReasonCode() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        SourceConnectorRegistry emptyRegistry = new SourceConnectorRegistry(List.of());
        IndexOrchestrator orchestratorWithoutConnector = new IndexOrchestrator(sourceDocumentJpaRepository,
                sourceConnectionJpaRepository, documentShareJpaRepository, documentEmbeddingJpaRepository,
                emptyRegistry, aiUsagePolicyService, documentParsingClient, transactionManager,
                Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC));

        IndexProcessingResult result = orchestratorWithoutConnector.processWithReasonCode(DOCUMENT_ID);

        assertThat(result.outcome()).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        assertThat(result.reasonCode()).isEqualTo("INELIGIBLE_AT_SOURCE_ACCESS_CONNECTOR_UNAVAILABLE");
        verifyNoInteractions(documentParsingClient);
    }

    @Test
    void skippedIneligibleAtTheVersionRecheckStageCarriesThatStagesFixedReasonCode() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        byte[] bytes = "pdf bytes".getBytes();
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.verified(bytes, "application/pdf", SOURCE_VERSION, false));
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(SOURCE_ID), any()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v2-changed",
                        Instant.now(), false));
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", IndexOrchestrator.EXPECTED_EMBEDDING_MODEL,
                        List.of(validChunk(0))));

        IndexProcessingResult result = orchestrator.processWithReasonCode(DOCUMENT_ID);

        assertThat(result.outcome()).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        assertThat(result.reasonCode()).isEqualTo("INELIGIBLE_AT_VERSION_RECHECK");
    }

    @Test
    void skippedIneligibleAtTheFinalGenerationFenceStageCarriesThatStagesFixedReasonCode() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        byte[] bytes = "pdf bytes".getBytes();
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.verified(bytes, "application/pdf", SOURCE_VERSION, false));
        givenSuccessfulPostParseRecheck(SOURCE_VERSION);
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", IndexOrchestrator.EXPECTED_EMBEDDING_MODEL,
                        List.of(validChunk(0))));
        SourceConnectionEntity advancedEpochConnection = activeConnection();
        advancedEpochConnection.bumpConnectionEpoch();
        when(sourceConnectionJpaRepository.findByIdForUpdate(SOURCE_ID))
                .thenReturn(Optional.of(advancedEpochConnection));

        IndexProcessingResult result = orchestrator.processWithReasonCode(DOCUMENT_ID);

        assertThat(result.outcome()).isEqualTo(IndexProcessingOutcome.SKIPPED_INELIGIBLE);
        assertThat(result.reasonCode()).isEqualTo("INELIGIBLE_AT_FINAL_GENERATION_FENCE");
    }

    @Test
    void indexedOutcomeCarriesNoAdditionalReasonCode() {
        givenActiveDocument("application/pdf");
        givenEligibleShareAndConnection(SecurityLevel.INTERNAL);
        byte[] bytes = "pdf bytes".getBytes();
        when(googleDriveConnector.fetchContent(any(), eq(SOURCE_ID), any(), eq(SOURCE_VERSION)))
                .thenReturn(SourceContentResult.verified(bytes, "application/pdf", SOURCE_VERSION, false));
        givenSuccessfulPostParseRecheck(SOURCE_VERSION);
        when(documentParsingClient.index(any(), any(), any()))
                .thenReturn(IndexOutcome.success("pdfminer-1", "1", IndexOrchestrator.EXPECTED_EMBEDDING_MODEL,
                        List.of(validChunk(0))));
        when(sourceConnectionJpaRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(activeConnection()));
        when(sourceDocumentJpaRepository.findByIdForUpdate(DOCUMENT_ID))
                .thenReturn(Optional.of(activeDocument("application/pdf")));
        when(documentShareJpaRepository.findByIdForUpdate(SHARE_ID))
                .thenReturn(Optional.of(activeShare(SecurityLevel.INTERNAL)));

        IndexProcessingResult result = orchestrator.processWithReasonCode(DOCUMENT_ID);

        assertThat(result.outcome()).isEqualTo(IndexProcessingOutcome.INDEXED);
        assertThat(result.reasonCode()).isNull();
    }

    private void givenActiveDocument(String mimeType) {
        when(sourceDocumentJpaRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(activeDocument(mimeType)));
    }

    private void givenEligibleShareAndConnection(SecurityLevel classification) {
        when(documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(DOCUMENT_ID))
                .thenReturn(Optional.of(activeShare(classification)));
        when(sourceConnectionJpaRepository.findById(SOURCE_ID)).thenReturn(Optional.of(activeConnection()));
        when(aiUsagePolicyService.evaluate(eq(classification), any(AiRequestContext.class)))
                .thenReturn(PolicyDecision.allow());
    }

    private static SourceDocumentEntity activeDocument(String mimeType) {
        return new SourceDocumentEntity(SOURCE_ID, "ext-doc-1", "Doc.pdf", mimeType, SOURCE_VERSION, null, "ACTIVE",
                "PENDING", null);
    }

    private static void setSourceVersion(SourceDocumentEntity document, String version) {
        // applySyncedMetadata는 index_status도 함께 재설정한다 - 이 테스트가 실제로 검증하려는
        // "Version이 달라졌다"만 정확히 반영한다(다른 필드는 원래 값 그대로 재사용).
        document.applySyncedMetadata(document.getName(), document.getMimeType(), version, null, "ACTIVE");
    }

    private static DocumentShareEntity activeShare(SecurityLevel classification) {
        DocumentShareEntity share = new DocumentShareEntity(OWNER, SOURCE_ID, DOCUMENT_ID, "ALL_AUTHENTICATED",
                classification.name(), "VIEW", Instant.now());
        // @GeneratedValue 필드라 Setter가 없다 - 실제 영속화 없이(순수 단위 Test) publishGeneration이
        // 요구하는 non-null shareId(발행 직전 Lock 재조회 Key)를 만들기 위한 최소한의 우회다.
        ReflectionTestUtils.setField(share, "id", SHARE_ID);
        return share;
    }

    /**
     * M11 후속 교정 - {@code publishGenerationAfterRecheck}이 AI 파싱 성공 후 호출하는
     * Post-parse 재확인을 성공으로 고정한다(Provider 접근/Version이 그대로임을 확인).
     */
    private void givenSuccessfulPostParseRecheck(String version) {
        when(googleDriveConnector.verifyCurrentMetadata(any(), eq(SOURCE_ID), any()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", version,
                        Instant.now(), false));
    }

    private static SourceConnectionEntity activeConnection() {
        return new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL", OWNER);
    }

    private static EmbeddingChunk validChunk(int index) {
        return new EmbeddingChunk(index, LocatorType.PAGE, String.valueOf(index + 1),
                java.util.Collections.nCopies(DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS, 0.01f), "a".repeat(64));
    }
}
