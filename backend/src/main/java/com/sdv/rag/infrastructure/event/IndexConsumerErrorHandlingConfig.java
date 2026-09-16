package com.sdv.rag.infrastructure.event;

import com.sdv.rag.infrastructure.persistence.repository.ProcessedEventJpaRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * M11 신규 - {@link IndexRequestedConsumer}의 경계 있는 재시도(Bounded Retry)/DLQ
 * 처리. Outbox Publisher처럼 별도 Claim Table을 새로 만드는 대신, Spring Kafka가 이미
 * 제공하는 {@link DefaultErrorHandler}({@link FixedBackOff})를 재사용한다 - {@link
 * IndexRequestedConsumer} Class Javadoc의 "경계 있는 재시도/DLQ" 절 참고.
 *
 * <p>Spring Boot의 자동 구성 {@code ConcurrentKafkaListenerContainerFactory}는 Context
 * 안에 정확히 하나의 {@link CommonErrorHandler} Bean이 있으면 그것을 자동으로 적용한다
 * (Spring Boot 표준 동작) - 그래서 이 Class는 별도 Listener Container Factory Bean을
 * 새로 정의하지 않는다.</p>
 *
 * <h2>M11 후속 교정(2026-09-16, 이번 작업 지시사항 B) - "복구됨"은 실제로 Durable한
 * Disposition이 있을 때만 주장한다</h2>
 * <p>경계 있는 재시도를 모두 소진하면(Bounded Retry Exhausted), 이 Handler는 먼저
 * {@link #tryRecordDurableDisposition}로 {@code processed_events}에 종결 행을
 * 기록한다 - 실제 {@code eventId}를 해석할 수 있으면 그 값을, 아니면(Malformed
 * Payload) 안전한 Broker 좌표(Topic/Partition/Offset)에서 결정론적으로 파생한
 * 합성 Key를 사용한다({@link #syntheticKeyFromBrokerCoordinates}) - 그래서
 * "eventId가 없다"는 이유만으로 DB 기록 자체를 건너뛰지 않는다. 이 DB 쓰기가
 * 실제로 커밋되면(Committed) 그 뒤 DLT 발행은 Best-effort로 남는다(swallow 유지 -
 * "A committed DB disposition may safely permit best-effort secondary DLT
 * failure"). DB 쓰기가 실패하면(연결 장애 등 진짜 오류) {@link
 * #confirmSanitizedDeadLetterDelivery}로 Fallback한다 - Kafka Broker 응답을
 * Bounded Timeout으로 실제 기다려(``Future.get``) 확인하고, 실패/Timeout/예외적
 * 완료 무엇이든 그대로 예외를 다시 던져 이 Recoverer 호출 전체가 실패로 전파되게
 * 한다("do not silently acknowledge success" - {@code send()}가 반환됐다는 사실만
 * 으로 비동기 전달이 성공했다고 주장하지 않는다).</p>
 *
 * <h2>신뢰할 수 없는 문서 상태를 함부로 바꾸지 않는다</h2>
 * <p>이 지점에는 {@code documentId}(있다면)만 있을 뿐, 그 실패가 어느 연결 Epoch/
 * 공유 Generation/Version을 겨냥한 것이었는지 재구성할 신뢰 가능한 스냅샷이 없다
 * ({@link com.sdv.rag.application.IndexOrchestrator}의 {@code Eligibility}와 달리).
 * 그래서 이 Handler는 더 이상 {@code source_documents.index_status}를 전혀 건드리지
 * 않는다 - "If terminal recovery lacks a trustworthy operation snapshot, record
 * the event failure without blindly changing document state"(이번 작업
 * 지시사항). 이벤트 자체가 종결 실패했다는 사실만 {@code processed_events}에
 * 안전하게 남긴다.</p>
 *
 * <h2>Content-Free DLT - 원본 Record/예외를 절대 옮기지 않는다</h2>
 * <p>표준 {@code DeadLetterPublishingRecoverer}는 원본 {@link ConsumerRecord}의
 * Key/Value(원본 Payload 전체 - 심지어 이 Consumer가 신뢰하지 않는 임의 추가 필드가
 * 섞여 있어도 그대로)를 그대로 전달하고 예외 메시지/Stack Trace를 Header로 덧붙인다 -
 * 이 계약은 "Do not copy original record bodies/headers, exception messages/stacks,
 * ... into DLT"를 어긴다. 이 Class는 표준 Recoverer를 쓰지 않고, 고정 허용 필드만
 * 담은 최소 JSON 봉투를 직접 조립해 발행한다({@link #buildSanitizedDeadLetterEnvelope}) -
 * 안전한 Broker 좌표(Topic/Partition/Offset)와 Best-effort {@code eventId}/{@code
 * documentId}, 그리고 고정 사유 코드 하나뿐이다. 이 봉투에 원본 Record의 Byte/Header가
 * 전혀 참여하지 않으므로, Payload에 주입된 임의 필드나 AI 서비스/예외가 만든 임의
 * 텍스트가 이 안에 나타날 방법이 없다.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "sdv.rag.index-consumer", name = "enabled", havingValue = "true")
public class IndexConsumerErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(IndexConsumerErrorHandlingConfig.class);
    /** 실제 {@code eventId}를 해석할 수 있었던 종결 실패. */
    private static final String FAILED_TERMINAL = "FAILED_TERMINAL";
    /** {@code eventId}를 해석할 수 없어 Broker 좌표 기반 합성 Key를 대신 쓴 종결 실패 - 구분 가능하되 여전히 Content-Free. */
    private static final String FAILED_TERMINAL_MALFORMED = "FAILED_TERMINAL_MALFORMED";
    /** 허용된 고정 사유 코드 - 예외 메시지/AI 서비스 응답 등 임의 텍스트를 절대 대신 쓰지 않는다. */
    static final String SAFE_REASON = "BOUNDED_RETRY_EXHAUSTED";
    private static final String DLT_SUFFIX = ".DLT";
    /** Fallback 경로(DB 기록 실패 시)의 DLT 전달 확인 상한 - "bounded timeout"(이번 작업 지시사항 B). */
    static final long DLT_CONFIRMATION_TIMEOUT_MS = 5000;

    @Bean
    public CommonErrorHandler indexConsumerErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate,
            ObjectMapper objectMapper, ProcessedEventJpaRepository processedEventJpaRepository,
            IndexConsumerProperties properties, PlatformTransactionManager transactionManager) {
        TransactionTemplate shortTransaction = new TransactionTemplate(transactionManager);
        ConsumerRecordRecoverer composite = buildRecoverer(kafkaTemplate, objectMapper, processedEventJpaRepository,
                shortTransaction);
        return new DefaultErrorHandler(composite,
                new FixedBackOff(properties.backoffMs(), Math.max(0, properties.maxAttempts() - 1L)));
    }

    /**
     * 실제로 {@link DefaultErrorHandler}에 연결되는 종결 복구 결정 로직 그 자체다 -
     * Test가 이 Method가 반환하는 정확히 같은 {@link ConsumerRecordRecoverer}를 직접
     * 호출해 "구성된 복구 경로"를 검증할 수 있도록 {@code @Bean} 조립에서 분리했다
     * (개별 정적 Helper만 따로 검증하는 것으로는 이 분기 로직 자체를 검증하지 못한다).
     */
    static ConsumerRecordRecoverer buildRecoverer(KafkaTemplate<Object, Object> kafkaTemplate,
            ObjectMapper objectMapper, ProcessedEventJpaRepository processedEventJpaRepository,
            TransactionTemplate shortTransaction) {
        return (record, exception) -> {
            boolean dbCommitted = tryRecordDurableDisposition(record, objectMapper, processedEventJpaRepository,
                    shortTransaction, Clock.systemUTC());
            if (dbCommitted) {
                // DB 기록이 이미 Authoritative Disposition이다 - DLT는 Best-effort 보조
                // 채널일 뿐이라 실패해도 삼킨다.
                try {
                    publishSanitizedDeadLetter(record, objectMapper, kafkaTemplate);
                } catch (RuntimeException dltPublishFailure) {
                    log.warn("dead-letter publish failed after the terminal DB disposition was already recorded, "
                            + "eventTopic={}", record.topic());
                }
                return;
            }
            // DB 기록 자체가 실패했다(진짜 오류 - eventId 부재는 더 이상 이 분기로 오지
            // 않는다) - 남은 유일한 안전장치는 확인된 DLT 전달이다. 실패/Timeout이면
            // 그대로 예외를 다시 던져 이 Record가 "복구됨"으로 처리되지 않게 한다.
            confirmSanitizedDeadLetterDelivery(record, objectMapper, kafkaTemplate);
        };
    }

    /**
     * {@code processed_events}에 이 이벤트의 종결 실패를 기록한다. 실제 {@code
     * eventId}를 해석할 수 있으면 그 값을, 아니면 안전한 Broker 좌표(Topic/Partition/
     * Offset - Content가 아니라 위치 식별자)에서 결정론적으로 파생한 합성 Key를
     * 쓴다({@link #syntheticKeyFromBrokerCoordinates}) - 그래서 Malformed Payload도
     * DB Disposition을 가질 수 있다("Persist a content-free disposition
     * identifiable by safe broker coordinates", 이번 작업 지시사항 B의 선택지 1).
     * {@link ProcessedEventJpaRepository#insertIfAbsent}(원자적 {@code INSERT ...
     * ON CONFLICT DO NOTHING})를 쓰므로 중복 실행도 안전하다 - "이미 성공/실패로
     * 기록된 행이 있다"와 "방금 내가 기록했다" 둘 다 이 Method 입장에서는 똑같이
     * "DB Disposition이 지금 존재한다"(반환값 {@code true})는 뜻이다. 이 문서 상태
     * ({@code source_documents})는 절대 건드리지 않는다(Class Javadoc "신뢰할 수 없는
     * 문서 상태" 절 참고).
     *
     * @return DB Disposition이 실제로 존재하게 됐으면 {@code true}. Insert 문 자체가
     *         예외를 던지면(연결 장애 등 진짜 오류) {@code false} - 호출자가 확인된
     *         DLT Fallback으로 넘어간다.
     */
    static boolean tryRecordDurableDisposition(ConsumerRecord<?, ?> record, ObjectMapper objectMapper,
            ProcessedEventJpaRepository processedEventJpaRepository, TransactionTemplate shortTransaction,
            Clock clock) {
        Object rawValue = record.value();
        String value = rawValue instanceof String s ? s : null;
        Long documentId = value == null ? null : IndexRequestedConsumer.extractDocumentIdBestEffort(value, objectMapper);
        UUID realEventId = value == null ? null : extractEventIdBestEffort(value, objectMapper);
        UUID dispositionKey = realEventId != null ? realEventId : syntheticKeyFromBrokerCoordinates(record);
        String outcome = realEventId != null ? FAILED_TERMINAL : FAILED_TERMINAL_MALFORMED;
        Instant now = clock.instant();
        try {
            shortTransaction.executeWithoutResult(status -> processedEventJpaRepository.insertIfAbsent(dispositionKey,
                    IndexRequestedConsumer.CONSUMER_NAME, outcome, SAFE_REASON, documentId, now));
            return true;
        } catch (RuntimeException dbFailure) {
            log.warn("terminal disposition write failed, falling back to confirmed dead-letter delivery, "
                    + "eventTopic={}, failureType={}", record.topic(), dbFailure.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Malformed Payload(실제 {@code eventId}를 해석할 수 없음) 전용 - 안전한 Broker
     * 좌표(Topic/Partition/Offset, Content가 아니다)만으로 결정론적 합성 Key를
     * 만든다. 같은 Broker 위치가 재전달돼도(같은 Offset) 항상 같은 Key가 나오므로
     * {@code insertIfAbsent}의 멱등성과 자연히 맞물린다.
     */
    private static UUID syntheticKeyFromBrokerCoordinates(ConsumerRecord<?, ?> record) {
        String coordinates = record.topic() + "|" + record.partition() + "|" + record.offset();
        return UUID.nameUUIDFromBytes(coordinates.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * M11 후속 교정 - 원본 {@link ConsumerRecord}의 Value/Header를 전혀 참여시키지
     * 않는 최소 허용 필드만으로 DLT에 발행한다(Class Javadoc "Content-Free DLT" 참고).
     * 기본 Spring Kafka DLT 명명 관례({@code <topic>.DLT})를 그대로 따른다. Best-effort
     * 경로 전용이다 - 전달 확인을 기다리지 않는다({@link #confirmSanitizedDeadLetterDelivery}
     * 와 구분).
     */
    static void publishSanitizedDeadLetter(ConsumerRecord<?, ?> record, ObjectMapper objectMapper,
            KafkaTemplate<Object, Object> kafkaTemplate) {
        String envelope = buildSanitizedDeadLetterEnvelope(record, objectMapper);
        kafkaTemplate.send(new ProducerRecord<>(record.topic() + DLT_SUFFIX, null, envelope));
    }

    /**
     * DB Disposition이 없을 때의 Fallback 경로 - "require confirmed sanitized DLT
     * delivery with a bounded timeout, propagating failure so the record is not
     * treated as recovered"(이번 작업 지시사항 B의 선택지 2). {@code send()}가
     * 반환한 {@link CompletableFuture}를 실제로 {@link #DLT_CONFIRMATION_TIMEOUT_MS}
     * 안에서 기다려("Distinguish 'DB disposition actually committed' from 'method
     * returned'") 확인한다 - Timeout/예외적 완료/즉시 실패 무엇이든 그대로
     * {@link IllegalStateException}으로 다시 던져 호출자(Recoverer)가 이 Record를
     * 회복됨으로 처리하지 않게 한다.
     */
    static void confirmSanitizedDeadLetterDelivery(ConsumerRecord<?, ?> record, ObjectMapper objectMapper,
            KafkaTemplate<Object, Object> kafkaTemplate) {
        String envelope = buildSanitizedDeadLetterEnvelope(record, objectMapper);
        CompletableFuture<?> future = kafkaTemplate.send(new ProducerRecord<>(record.topic() + DLT_SUFFIX, null,
                envelope));
        try {
            future.get(DLT_CONFIRMATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("dead-letter delivery confirmation was interrupted");
        } catch (ExecutionException | TimeoutException deliveryNotConfirmed) {
            throw new IllegalStateException(
                    "dead-letter delivery could not be confirmed within the bounded timeout");
        }
    }

    /**
     * 허용된 필드만 담는다: 고정 사유 코드, 안전한 Broker 좌표(원본 Topic/Partition/
     * Offset - Content가 아니라 위치 식별자다), Best-effort {@code eventId}/{@code
     * documentId}(둘 다 파싱 가능할 때만, 실패하면 {@code null}). 원본 Value/Header/
     * 예외 메시지/Stack Trace는 절대 포함하지 않는다 - Payload에 주입된 임의 추가
     * 필드가 있어도 이 봉투를 구성하는 과정 자체가 그 값을 읽지 않으므로 나타날 수
     * 없다.
     */
    static String buildSanitizedDeadLetterEnvelope(ConsumerRecord<?, ?> record, ObjectMapper objectMapper) {
        Object rawValue = record.value();
        String value = rawValue instanceof String s ? s : null;
        Long documentId = value == null ? null : IndexRequestedConsumer.extractDocumentIdBestEffort(value, objectMapper);
        UUID eventId = value == null ? null : extractEventIdBestEffort(value, objectMapper);
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("reasonCode", SAFE_REASON);
        safe.put("sourceTopic", record.topic());
        safe.put("sourcePartition", record.partition());
        safe.put("sourceOffset", record.offset());
        safe.put("eventId", eventId == null ? null : eventId.toString());
        safe.put("documentId", documentId == null ? null : documentId.toString());
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (RuntimeException serializationFailure) {
            // 이 Map 자체는 전부 안전한 고정/원시 값뿐이라 직렬화가 실패할 이유가 없어야
            // 하지만(Best-effort 방어), 혹시라도 실패하면 여전히 Content-Free한 최소
            // Fallback을 반환한다 - 원본 값/예외 메시지를 절대 대신 쓰지 않는다.
            return "{\"reasonCode\":\"" + SAFE_REASON + "\"}";
        }
    }

    private static UUID extractEventIdBestEffort(String value, ObjectMapper objectMapper) {
        try {
            Map<?, ?> raw = objectMapper.readValue(value, Map.class);
            Object eventIdRaw = raw.get("eventId");
            return eventIdRaw instanceof String s ? UUID.fromString(s) : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
