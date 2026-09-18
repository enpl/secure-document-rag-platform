package com.sdv.rag.infrastructure.event;

import com.sdv.audit.application.AuditService;
import com.sdv.rag.application.IndexOrchestrator;
import com.sdv.rag.application.IndexProcessingOutcome;
import com.sdv.rag.application.IndexProcessingResult;
import com.sdv.rag.infrastructure.persistence.entity.ProcessedEventEntity;
import com.sdv.rag.infrastructure.persistence.repository.ProcessedEventJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M11 신규 - 순수 단위 테스트(Mockito)로 {@link IndexRequestedConsumer}의 Payload
 * 해석/이벤트 타입 필터링/멱등성 확인/처리 결과 기록 분기를 검증한다. 실제 Kafka
 * Broker/DB는 {@code IndexRequestedConsumerIntegrationTest}(Testcontainers, 실제
 * 배선된 경로)가 담당한다.
 *
 * <p>M11 후속 교정(이번 작업 지시사항 C) - {@code processedEventJpaRepository.save(...)}
 * 검증을 {@code insertIfAbsent(...)} 검증으로 교체했다(생성자가 이제 {@link
 * PlatformTransactionManager}도 받는다 - 원장 기록을 자신의 짧은 Transaction으로
 * 감싸기 위함, Class Javadoc 참고). {@link PlatformTransactionManager}는 Mockito
 * 기본 Mock(모든 메서드가 no-op)으로 충분하다 - {@code TransactionTemplate.execute}가
 * 그 위에서 그대로 동기적으로 Callback을 실행한다({@code IndexOrchestratorTest}와 동일한
 * 이유).
 */
@ExtendWith(MockitoExtension.class)
class IndexRequestedConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Long DOCUMENT_ID = 42L;

    @Mock
    private IndexOrchestrator indexOrchestrator;
    @Mock
    private ProcessedEventJpaRepository processedEventJpaRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ObjectMapper objectMapper;
    private IndexRequestedConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
        consumer = new IndexRequestedConsumer(indexOrchestrator, processedEventJpaRepository, auditService,
                objectMapper, transactionManager, fixedClock);
    }

    @Test
    void malformedJsonPayloadIsAuditedWithoutTouchingTheOrchestratorOrProcessedEvents() {
        consumer.onMessage("not valid json{{{");

        verifyNoInteractions(indexOrchestrator, processedEventJpaRepository);
        verify(auditService).record(eq("system"), eq("INDEX_EVENT_MALFORMED"), any(), eq("FAILURE"),
                eq("MALFORMED_PAYLOAD"), eq(Map.of()));
    }

    @Test
    void aWellFormedPayloadMissingRequiredFieldsIsTreatedAsMalformed() {
        String payload = indexRequestedPayloadMissingDocumentId();

        consumer.onMessage(payload);

        verifyNoInteractions(indexOrchestrator, processedEventJpaRepository);
        verify(auditService).record(eq("system"), eq("INDEX_EVENT_MALFORMED"), any(), any(), any(), any());
    }

    @Test
    void aNonTriggeringCatalogSyncEventTypeIsIgnoredWithoutInvokingTheOrchestrator() {
        String payload = permissionChangedPayload();

        consumer.onMessage(payload);

        verifyNoInteractions(indexOrchestrator, processedEventJpaRepository, auditService);
    }

    /**
     * M11 후속 교정 - "complete incremental indexing": {@code SOURCE_DOCUMENT_CHANGED}
     * (통상적인 Content Version 변경)도 이제 이 Consumer가 {@link
     * IndexOrchestrator#processWithReasonCode}로 넘긴다. 이 Test 자체는 Mockito 단위
     * Test라 실제 자격 판단은 검증하지 않는다(그건
     * {@code IndexOrchestratorTest}/{@code IndexRequestedConsumerIntegrationTest} 몫이다) -
     * 여기서는 오직 "이 이벤트 타입이 더 이상 조용히 무시되지 않는다"만 확인한다.
     */
    @Test
    void aSourceDocumentChangedEventIsProcessedJustLikeAnIndexRequestedEvent() {
        UUID changedEventId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(processedEventJpaRepository.existsById(any())).thenReturn(false);
        when(indexOrchestrator.processWithReasonCode(DOCUMENT_ID))
                .thenReturn(new IndexProcessingResult(IndexProcessingOutcome.INDEXED, null));

        consumer.onMessage(catalogSyncPayload(changedEventId));

        verify(indexOrchestrator).processWithReasonCode(DOCUMENT_ID);
        verify(processedEventJpaRepository).insertIfAbsent(eq(changedEventId), eq(IndexRequestedConsumer.CONSUMER_NAME),
                eq("INDEXED"), isNull(), eq(DOCUMENT_ID), any());
    }

    @Test
    void aDuplicateDeliveryOfAnAlreadyProcessedEventDoesNotReprocess() {
        when(processedEventJpaRepository.existsById(new ProcessedEventEntity.Key(EVENT_ID,
                IndexRequestedConsumer.CONSUMER_NAME))).thenReturn(true);

        consumer.onMessage(indexRequestedPayload());

        verifyNoInteractions(indexOrchestrator);
        verify(processedEventJpaRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aFreshIndexRequestedEventIsProcessedAndRecordedWithTheMatchingOutcomeCode() {
        when(processedEventJpaRepository.existsById(any())).thenReturn(false);
        when(indexOrchestrator.processWithReasonCode(DOCUMENT_ID))
                .thenReturn(new IndexProcessingResult(IndexProcessingOutcome.INDEXED, null));

        consumer.onMessage(indexRequestedPayload());

        verify(indexOrchestrator).processWithReasonCode(DOCUMENT_ID);
        verify(processedEventJpaRepository).insertIfAbsent(eq(EVENT_ID), eq(IndexRequestedConsumer.CONSUMER_NAME),
                eq("INDEXED"), isNull(), eq(DOCUMENT_ID), any());
    }

    @Test
    void aSkippedUnsupportedOutcomeIsRecordedWithItsOwnCode() {
        when(processedEventJpaRepository.existsById(any())).thenReturn(false);
        when(indexOrchestrator.processWithReasonCode(DOCUMENT_ID))
                .thenReturn(new IndexProcessingResult(IndexProcessingOutcome.SKIPPED_UNSUPPORTED,
                        "AI_SERVICE_UNSUPPORTED_FORMAT"));

        consumer.onMessage(indexRequestedPayload());

        verify(processedEventJpaRepository).insertIfAbsent(eq(EVENT_ID), eq(IndexRequestedConsumer.CONSUMER_NAME),
                eq("SKIPPED_UNSUPPORTED"), eq("AI_SERVICE_UNSUPPORTED_FORMAT"), eq(DOCUMENT_ID), any());
    }

    /**
     * M17 진단 교정 - 이전에는 {@code reason_code}를 이 자리에서 항상 {@code null}로
     * 고정해 기록했다({@code IndexOrchestrator}가 어떤 값을 판정했든 무시됐다). 이제
     * {@link IndexOrchestrator#processWithReasonCode}가 돌려준 고정 단계 사유 코드가
     * 그대로 원장에 옮겨진다는 것을 확인한다 - 이 Test는 순수 단위 Test라 실제
     * {@code IndexOrchestrator}의 판정 로직 자체는 검증하지 않는다(그건 {@code
     * IndexOrchestratorTest} 몫이다).
     */
    @Test
    void aSkippedIneligibleOutcomeIsRecordedWithItsStageReasonCode() {
        when(processedEventJpaRepository.existsById(any())).thenReturn(false);
        when(indexOrchestrator.processWithReasonCode(DOCUMENT_ID))
                .thenReturn(new IndexProcessingResult(IndexProcessingOutcome.SKIPPED_INELIGIBLE,
                        "INELIGIBLE_AT_ELIGIBILITY_CHECK"));

        consumer.onMessage(indexRequestedPayload());

        verify(processedEventJpaRepository).insertIfAbsent(eq(EVENT_ID), eq(IndexRequestedConsumer.CONSUMER_NAME),
                eq("SKIPPED_INELIGIBLE"), eq("INELIGIBLE_AT_ELIGIBILITY_CHECK"), eq(DOCUMENT_ID), any());
    }

    private static String indexRequestedPayload() {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"INDEX_REQUESTED\",\"schemaVersion\":\"1\","
                + "\"sourceId\":\"7\",\"sourceAccountSubject\":\"owner\",\"internalDocumentId\":\"" + DOCUMENT_ID
                + "\",\"externalDocumentId\":\"ext-1\",\"sourceVersion\":\"v1\","
                + "\"occurredAt\":\"2026-09-16T00:00:00Z\",\"traceId\":\"trace-1\"}";
    }

    private static String indexRequestedPayloadMissingDocumentId() {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"INDEX_REQUESTED\",\"schemaVersion\":\"1\","
                + "\"sourceId\":\"7\"}";
    }

    private static String catalogSyncPayload(UUID eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"SOURCE_DOCUMENT_CHANGED\","
                + "\"schemaVersion\":\"1\",\"sourceId\":\"7\",\"internalDocumentId\":\"" + DOCUMENT_ID
                + "\",\"externalDocumentId\":\"ext-1\",\"sourceVersion\":\"v1\","
                + "\"occurredAt\":\"2026-09-16T00:00:00Z\",\"traceId\":\"trace-1\"}";
    }

    private static String permissionChangedPayload() {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"SOURCE_PERMISSION_CHANGED\","
                + "\"schemaVersion\":\"1\",\"sourceId\":\"7\",\"internalDocumentId\":\"" + DOCUMENT_ID + "\"}";
    }
}
