package com.sdv.rag.infrastructure.event;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sdv.rag.infrastructure.persistence.entity.ProcessedEventEntity;
import com.sdv.rag.infrastructure.persistence.repository.ProcessedEventJpaRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M11 후속 교정 검증(2026-09-16, 이번 작업 지시사항 B, "Guarantee a durable disposition
 * before treating recovery as complete") - 순수 단위 테스트(Mockito, 실제 Kafka Broker/
 * DB 없음)로 {@link IndexConsumerErrorHandlingConfig#buildRecoverer}가 반환하는, 실제
 * {@code @Bean}이 {@link org.springframework.kafka.listener.DefaultErrorHandler}에
 * 연결하는 것과 정확히 같은 {@link ConsumerRecordRecoverer}(=구성된 복구 경로)를 직접
 * 호출해 검증한다 - {@code buildSanitizedDeadLetterEnvelope} 같은 개별 Helper만 따로
 * 검증하는 것으로는 "DB 실패 시에만 확인된 DLT로 Fallback한다"는 분기 로직 자체를
 * 검증하지 못하기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class IndexConsumerErrorHandlingConfigTest {

    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Long DOCUMENT_ID = 42L;
    private static final String PLAINTEXT_CANARY = "SECRET-CANARY-do-not-leak-9f3a";
    private static final RuntimeException SIMULATED_DB_FAILURE = new RuntimeException("database is down: "
            + PLAINTEXT_CANARY);
    private static final RuntimeException SIMULATED_BROKER_REJECTION = new RuntimeException("broker rejected: "
            + PLAINTEXT_CANARY);

    @Mock
    private ProcessedEventJpaRepository processedEventJpaRepository;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private ObjectMapper objectMapper;
    private TransactionTemplate shortTransaction;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        shortTransaction = new TransactionTemplate(transactionManager);
        logCapture = new ListAppender<>();
        logCapture.start();
        ((Logger) LoggerFactoryHolder.logbackLogger()).addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactoryHolder.logbackLogger()).detachAppender(logCapture);
    }

    // ------------------------------------------------------------------
    // B 인수 기준 5개 - "구성된 복구 경로"(buildRecoverer) 자체를 직접 호출한다.
    // ------------------------------------------------------------------

    /** "Malformed/missing event ID + audit failure + DLT synchronous failure: not recovered." */
    @Test
    void malformedEventIdWithAuditFailureAndSynchronousDltFailureIsNotRecovered() {
        doThrow(SIMULATED_DB_FAILURE).when(processedEventJpaRepository).insertIfAbsent(any(), any(), any(), any(),
                any(), any());
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(SIMULATED_BROKER_REJECTION);
        ConsumerRecordRecoverer recoverer = IndexConsumerErrorHandlingConfig.buildRecoverer(kafkaTemplate,
                objectMapper, processedEventJpaRepository, shortTransaction);
        ConsumerRecord<Object, Object> record = malformedRecordWithCanary();

        assertThatThrownBy(() -> recoverer.accept(record, new RuntimeException("listener failed")))
                .as("neither a DB disposition nor a confirmed DLT delivery exists - must not be treated as recovered")
                .isInstanceOf(RuntimeException.class);

        assertNoCanaryLeaked();
    }

    /** "The same case with an exceptionally completed send future: not recovered." */
    @Test
    void malformedEventIdWithAuditFailureAndExceptionallyCompletedSendFutureIsNotRecovered() {
        doThrow(SIMULATED_DB_FAILURE).when(processedEventJpaRepository).insertIfAbsent(any(), any(), any(), any(),
                any(), any());
        CompletableFuture<SendResult<Object, Object>> exceptionallyCompleted = new CompletableFuture<>();
        exceptionallyCompleted.completeExceptionally(SIMULATED_BROKER_REJECTION);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(exceptionallyCompleted);
        ConsumerRecordRecoverer recoverer = IndexConsumerErrorHandlingConfig.buildRecoverer(kafkaTemplate,
                objectMapper, processedEventJpaRepository, shortTransaction);
        ConsumerRecord<Object, Object> record = malformedRecordWithCanary();

        assertThatThrownBy(() -> recoverer.accept(record, new RuntimeException("listener failed")))
                .as("send() returning a future is not proof of delivery - an exceptionally completed future must "
                        + "still count as not recovered")
                .isInstanceOf(RuntimeException.class);

        assertNoCanaryLeaked();
    }

    /** "Confirmed fallback delivery: recovery may complete." */
    @Test
    void confirmedFallbackDeliverySucceedsWhenTheDbDispositionFails() {
        doThrow(SIMULATED_DB_FAILURE).when(processedEventJpaRepository).insertIfAbsent(any(), any(), any(), any(),
                any(), any());
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(successfulSend());
        ConsumerRecordRecoverer recoverer = IndexConsumerErrorHandlingConfig.buildRecoverer(kafkaTemplate,
                objectMapper, processedEventJpaRepository, shortTransaction);
        ConsumerRecord<Object, Object> record = malformedRecordWithCanary();

        recoverer.accept(record, new RuntimeException("listener failed"));

        assertNoCanaryLeaked();
    }

    /** "A committed DB disposition may safely permit best-effort secondary DLT failure." */
    @Test
    void committedDbDispositionPermitsBestEffortSecondaryDltFailure() {
        ConsumerRecordRecoverer recoverer = IndexConsumerErrorHandlingConfig.buildRecoverer(kafkaTemplate,
                objectMapper, processedEventJpaRepository, shortTransaction);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(SIMULATED_BROKER_REJECTION);
        ConsumerRecord<Object, Object> record = wellFormedRecordWithCanaryInAiReasonField();

        recoverer.accept(record, new RuntimeException("listener failed"));

        verify(processedEventJpaRepository).insertIfAbsent(org.mockito.ArgumentMatchers.eq(EVENT_ID),
                org.mockito.ArgumentMatchers.eq(IndexRequestedConsumer.CONSUMER_NAME),
                org.mockito.ArgumentMatchers.eq("FAILED_TERMINAL"),
                org.mockito.ArgumentMatchers.eq(IndexConsumerErrorHandlingConfig.SAFE_REASON),
                org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), any());
        assertNoCanaryLeaked();
    }

    /** DB 기록이 성공하면 실제로 DLT 발행도 시도한다(Best-effort) - 성공 경로 자체의 배선 확인. */
    @Test
    void committedDbDispositionStillAttemptsBestEffortDeadLetterPublication() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(successfulSend());
        ConsumerRecordRecoverer recoverer = IndexConsumerErrorHandlingConfig.buildRecoverer(kafkaTemplate,
                objectMapper, processedEventJpaRepository, shortTransaction);
        ConsumerRecord<Object, Object> record = wellFormedRecordWithCanaryInAiReasonField();

        recoverer.accept(record, new RuntimeException("listener failed"));

        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(1)).send(captor.capture());
        assertThat(String.valueOf(captor.getValue().value())).doesNotContain(PLAINTEXT_CANARY);
        assertNoCanaryLeaked();
    }

    /** 낡은 종결 실패(2026-09-16 이전 문서 상태 변이)가 사라졌음을 고정한다 - source_documents는 이제 전혀 건드리지 않는다. */
    @Test
    void recoveryNeverTouchesDocumentState() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(successfulSend());
        ConsumerRecordRecoverer recoverer = IndexConsumerErrorHandlingConfig.buildRecoverer(kafkaTemplate,
                objectMapper, processedEventJpaRepository, shortTransaction);
        ConsumerRecord<Object, Object> record = wellFormedRecordWithCanaryInAiReasonField();

        recoverer.accept(record, new RuntimeException("listener failed"));

        // source_documents에 대한 어떤 Repository도 이 Class에 더 이상 주입되지 않는다(생성자
        // 시그니처 자체가 그것을 강제한다) - "record the event failure without blindly
        // changing document state." 이 Test는 그 설계 결정이 유지됨을 컴파일 타임/실행 모두로
        // 재확인한다(SourceDocumentJpaRepository를 전혀 Mock하지 않고도 이 Test가 통과한다).
        verify(processedEventJpaRepository).insertIfAbsent(any(), any(), any(), any(), any(), any());
    }

    private void assertNoCanaryLeaked() {
        // DLT로 실제 전송된 Payload에 Canary가 없는지 확인한다(호출이 있었을 때만).
        ArgumentCaptor<ProducerRecord> sentCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeast(0)).send(sentCaptor.capture());
        for (ProducerRecord<?, ?> sent : sentCaptor.getAllValues()) {
            assertThat(String.valueOf(sent.value())).doesNotContain(PLAINTEXT_CANARY);
        }
        // processed_events에 실제로 기록하려 했던 documentId/outcome/reasonCode 인자에도 없어야 한다.
        ArgumentCaptor<String> outcomeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(processedEventJpaRepository, org.mockito.Mockito.atLeast(0)).insertIfAbsent(any(), any(),
                outcomeCaptor.capture(), reasonCaptor.capture(), any(), any());
        for (String outcome : outcomeCaptor.getAllValues()) {
            assertThat(outcome).doesNotContain(PLAINTEXT_CANARY);
        }
        for (String reason : reasonCaptor.getAllValues()) {
            assertThat(reason).doesNotContain(PLAINTEXT_CANARY);
        }
        // 이 Test 안에서 캡처된 Application 로그 어디에도 Canary가 없어야 한다.
        assertThat(logCapture.list).noneMatch(event -> event.getFormattedMessage().contains(PLAINTEXT_CANARY));
    }

    private static CompletableFuture<SendResult<Object, Object>> successfulSend() {
        RecordMetadata metadata = new RecordMetadata(
                new org.apache.kafka.common.TopicPartition("sdv.source.events.DLT", 0), 0, 0, 0, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(null, metadata));
    }

    private static ConsumerRecord<Object, Object> malformedRecordWithCanary() {
        return new ConsumerRecord<>("sdv.source.events", 1, 5L, null, "not valid json{{{" + PLAINTEXT_CANARY);
    }

    /** 실제 eventId/documentId는 파싱 가능하지만, 신뢰하지 않는 추가 필드에 Canary가 심겨 있다. */
    private static ConsumerRecord<Object, Object> wellFormedRecordWithCanaryInAiReasonField() {
        String value = "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"INDEX_REQUESTED\","
                + "\"internalDocumentId\":\"" + DOCUMENT_ID + "\",\"aiServiceReason\":\"" + PLAINTEXT_CANARY + "\"}";
        return new ConsumerRecord<>("sdv.source.events", 0, 1L, "key", value);
    }

    // ------------------------------------------------------------------
    // Content-Free DLT 봉투 조립 자체(Helper 단위) - 여전히 유효한 검증이나,
    // 위 buildRecoverer 경로 검증을 대체하지 않는다(별도로 함께 유지한다).
    // ------------------------------------------------------------------

    @Test
    void sanitizedDeadLetterEnvelopeNeverContainsAnInjectedCanaryFieldFromTheOriginalPayload() {
        String poisonedPayload = "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"INDEX_REQUESTED\","
                + "\"internalDocumentId\":\"" + DOCUMENT_ID + "\",\"attackerInjectedField\":\"" + PLAINTEXT_CANARY
                + "\"}";
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("sdv.source.events", 3, 77L, "key",
                poisonedPayload);

        String envelope = IndexConsumerErrorHandlingConfig.buildSanitizedDeadLetterEnvelope(record, objectMapper);

        assertThat(envelope).doesNotContain(PLAINTEXT_CANARY);
        assertThat(envelope).doesNotContain(poisonedPayload);
        assertThat(envelope).contains("\"reasonCode\":\"" + IndexConsumerErrorHandlingConfig.SAFE_REASON + "\"");
        assertThat(envelope).contains("\"sourceTopic\":\"sdv.source.events\"");
        assertThat(envelope).contains("\"sourcePartition\":3");
        assertThat(envelope).contains("\"sourceOffset\":77");
        assertThat(envelope).contains("\"eventId\":\"" + EVENT_ID + "\"");
        assertThat(envelope).contains("\"documentId\":\"" + DOCUMENT_ID + "\"");
    }

    @Test
    void sanitizedDeadLetterEnvelopeHandlesMalformedPayloadSafely() {
        ConsumerRecord<Object, Object> record = malformedRecordWithCanary();

        String envelope = IndexConsumerErrorHandlingConfig.buildSanitizedDeadLetterEnvelope(record, objectMapper);

        assertThat(envelope).doesNotContain(PLAINTEXT_CANARY);
        assertThat(envelope).contains("\"reasonCode\":\"" + IndexConsumerErrorHandlingConfig.SAFE_REASON + "\"");
        assertThat(envelope).contains("\"eventId\":null");
    }

    /** synthetic Key는 Broker 좌표에만 의존한다 - 같은 위치가 재전달되면 항상 같은 Key다(멱등성의 기반). */
    @Test
    void terminalDispositionForMalformedPayloadUsesADeterministicSyntheticKeyAndTheMalformedOutcome() {
        ConsumerRecordRecoverer recoverer = IndexConsumerErrorHandlingConfig.buildRecoverer(kafkaTemplate,
                objectMapper, processedEventJpaRepository, shortTransaction);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(successfulSend());
        ConsumerRecord<Object, Object> record = malformedRecordWithCanary();

        recoverer.accept(record, new RuntimeException("listener failed"));
        recoverer.accept(malformedRecordWithCanary(), new RuntimeException("listener failed again"));

        ArgumentCaptor<UUID> keyCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(processedEventJpaRepository, times(2)).insertIfAbsent(keyCaptor.capture(), any(), any(), any(), any(),
                any());
        List<UUID> keys = keyCaptor.getAllValues();
        assertThat(keys.get(0)).as("the same broker coordinates must always yield the same synthetic key")
                .isEqualTo(keys.get(1));
    }

    /** Logback 접근을 한 곳에 모아 SLF4J 구현에 대한 정적 의존을 최소화한다. */
    private static final class LoggerFactoryHolder {
        static org.slf4j.Logger logbackLogger() {
            return org.slf4j.LoggerFactory.getLogger(IndexConsumerErrorHandlingConfig.class);
        }
    }
}
