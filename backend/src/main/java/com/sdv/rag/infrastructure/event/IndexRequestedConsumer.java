package com.sdv.rag.infrastructure.event;

import com.sdv.event.domain.IndexRequestedEvent;
import com.sdv.event.domain.SourceDocumentChangedEvent;
import com.sdv.rag.application.IndexOrchestrator;
import com.sdv.rag.application.IndexProcessingOutcome;
import com.sdv.rag.application.TransientIndexingException;
import com.sdv.rag.infrastructure.persistence.entity.ProcessedEventEntity;
import com.sdv.rag.infrastructure.persistence.repository.ProcessedEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * M11 신규(이 작업 지시사항의 3번, {@code backend/src/main/java/com/sdv/rag/infrastructure/event/IndexRequestedConsumer.java}
 * 지정 경로) - {@link IndexRequestedEvent}와 {@link SourceDocumentChangedEvent}를
 * 소비해 {@link IndexOrchestrator}로 넘기는 Kafka Listener.
 *
 * <h2>M11 후속 교정 - 통상적인 Content 변경도 재색인을 촉발한다</h2>
 * <p>{@link SourceDocumentChangedEvent}(Catalog Sync가 실제 Version 변경을 관측했을
 * 때 발행)도 이제 {@link IndexOrchestrator#process}를 호출한다 - 그래야 이미 공유된
 * 문서의 통상적인 Content 갱신이 새 {@code IndexRequestedEvent} 없이도 재색인된다.
 * 이것이 "Private metadata events may remain catalog events, but must not trigger
 * private content fetch/indexing"을 어기지 않는 이유는, {@link IndexOrchestrator#process}
 * 자신이 매번 {@code document_shares}/{@code source_connections}를 새로 조회해
 * 자격(활성+비차단 공유 AND AI 정책 허용)을 먼저 판단하고, 그 판단을 통과하지 못하면
 * Google Fetch 자체를 절대 호출하지 않기 때문이다 - 그래서 한 번도 공유된 적 없는
 * 비공개 문서의 Catalog Sync는 여전히 어떤 Content Fetch도 유발하지 않는다. {@link
 * com.sdv.event.infrastructure.OutboxEventPublisher}가 발행하는 같은 Topic({@code
 * sdv.source.events})의 나머지 두 Catalog Sync 이벤트({@code SOURCE_DOCUMENT_DELETED}/
 * {@code SOURCE_PERMISSION_CHANGED})는 계속 조용히 무시한다 - 삭제는 이미
 * {@code SourceDeletionService}/{@code SourceSharingService}의 즉시 Embedding 정리
 * 경로가 처리하고, 권한 변경은 Content 자체를 바꾸지 않는다(재색인할 이유가 없다).
 *
 * <h2>기본 비활성화</h2>
 * <p>{@code sdv.rag.index-consumer.enabled}가 명시적으로 {@code true}일 때만 이 Bean
 * 자체가 등록된다 - {@link com.sdv.event.infrastructure.OutboxEventPublisher}와 동일한
 * 패턴. 격리된 Test Kafka Broker를 쓰는 테스트만 이 값을 Override한다.</p>
 *
 * <h2>멱등성 - "받은 이벤트가 곧 끝난 작업은 아니다"</h2>
 * <p>Kafka는 At-Least-Once 전달만 보장한다 - 같은 이벤트가 두 번 이상 올 수 있다. 이미
 * {@code processed_events}에 이 (eventId, consumer) 조합이 있으면 다시 처리하지 않고
 * 조용히 넘어간다. 이 확인은 정확성을 위한 필수 Lock이 아니다 - 진짜 정확성은
 * {@link IndexOrchestrator#process}가 매번 자격을 새로 조회하고, 최종 발행이
 * {@code replaceGeneration}(원자적 세대 교체)으로 보장한다. 이 확인은 순전히 이미
 * 끝난 무거운 작업(Google Fetch + Python Parse/Chunk/Embed)을 다시 하지 않기 위한
 * 최적화 겸 감사(Audit) 기록이다.</p>
 *
 * <h2>경계 있는 재시도/DLQ - Spring Kafka의 기존 기능을 재사용한다</h2>
 * <p>이 Listener는 스스로 재시도 횟수를 세지 않는다 - {@link IndexOrchestrator#process}가
 * {@link TransientIndexingException}을 던지면 그대로 다시 던지고, {@code
 * IndexConsumerErrorHandlingConfig}가 등록하는 {@code DefaultErrorHandler}(경계 있는
 * {@code FixedBackOff})가 자동으로 재전달을 유도한다 - 한 Listener 호출 안에서 끝나는
 * 작업이라(Kafka Poll 주기를 넘어 상태를 들고 있지 않는다), Outbox Publisher처럼 별도
 * Claim Token Table을 새로 만들 필요가 없다("work spans transactions"가 아니다). 경계를
 * 넘기면 그 Handler가 DLT Topic 발행 + DB 종결 실패 기록으로 넘긴다.</p>
 *
 * <h2>Malformed Payload - 조용히 재시도 루프에 빠지지 않는다</h2>
 * <p>Payload 자체가 해석 불가능하면(JSON 자체가 깨졌거나 필수 필드가 없음) 어떤
 * {@code eventId}도 신뢰할 수 없으므로 {@code processed_events}에 기록할 Key가 없다 -
 * 대신 {@link com.sdv.audit.application.AuditService}에 원본 Payload 없이 안전한
 * 사유만 남기고 조용히 넘어간다(재시도해도 절대 해석 가능해지지 않는 메시지를 무한히
 * 재시도하지 않는다 - "Poison messages must not loop forever or be silently
 * acknowledged without a durable disposition", 이 Audit 기록이 그 Durable
 * Disposition이다).</p>
 */
@Component
@ConditionalOnProperty(prefix = "sdv.rag.index-consumer", name = "enabled", havingValue = "true")
public class IndexRequestedConsumer {

    /** {@code processed_events.consumer_name} - 지금은 유일한 Consumer다. */
    public static final String CONSUMER_NAME = "rag-index-orchestrator";

    private static final Logger log = LoggerFactory.getLogger(IndexRequestedConsumer.class);

    static final String OUTCOME_INDEXED = "INDEXED";
    static final String OUTCOME_SKIPPED_INELIGIBLE = "SKIPPED_INELIGIBLE";
    static final String OUTCOME_SKIPPED_UNSUPPORTED = "SKIPPED_UNSUPPORTED";
    static final String OUTCOME_SKIPPED_NO_TEXT = "SKIPPED_NO_TEXT";
    static final String OUTCOME_IGNORED_OTHER_EVENT_TYPE = "IGNORED_OTHER_EVENT_TYPE";

    private final IndexOrchestrator indexOrchestrator;
    private final ProcessedEventJpaRepository processedEventJpaRepository;
    private final com.sdv.audit.application.AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate ledgerTransaction;
    private final Clock clock;

    @Autowired
    public IndexRequestedConsumer(IndexOrchestrator indexOrchestrator,
            ProcessedEventJpaRepository processedEventJpaRepository,
            com.sdv.audit.application.AuditService auditService, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(indexOrchestrator, processedEventJpaRepository, auditService, objectMapper, transactionManager,
                Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}을 주입하기 위한 패키지 전용 생성자. */
    IndexRequestedConsumer(IndexOrchestrator indexOrchestrator, ProcessedEventJpaRepository processedEventJpaRepository,
            com.sdv.audit.application.AuditService auditService, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.indexOrchestrator = indexOrchestrator;
        this.processedEventJpaRepository = processedEventJpaRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        // M11 후속 교정(이번 작업 지시사항 A/C) - onMessage() 자신은 절대 @Transactional이면
        // 안 된다(그러면 process() 안의 Google/Python 외부 호출 전체가 이 Ambient Transaction
        // 아래에서 실행돼 "Do not hold DB locks during external calls"를 어긴다). 대신 이
        // 원장 기록 한 줄만(모든 외부 호출과 IndexOrchestrator 자신의 Publish Transaction이
        // 이미 끝난 뒤) 독립된 짧은 Transaction으로 감싼다 - insertIfAbsent 같은 커스텀
        // {@code @Modifying} Query는 (기존 {@code save()}와 달리) Spring Data가 자동으로
        // Transaction을 열어주지 않는다(Transaction 없이 호출하면 {@code
        // TransactionRequiredException}) - 이번 교정에서 실제로 재현/확인했다.
        this.ledgerTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @KafkaListener(topics = "${sdv.rag.index-consumer.topic:sdv.source.events}",
            groupId = "${sdv.rag.index-consumer.group-id:sdv-rag-index-orchestrator}")
    public void onMessage(String value) {
        ParsedEnvelope parsed = parse(value);
        if (parsed == null) {
            // 원본 Payload는 절대 옮기지 않는다 - 이미 해석에 실패한 값이라 어떤 안전한
            // 부분도 신뢰할 수 없다.
            auditService.record("system", "INDEX_EVENT_MALFORMED", "topic:index-requested", "FAILURE",
                    "MALFORMED_PAYLOAD", Map.of());
            return;
        }
        if (!isTriggeringEventType(parsed.eventType())) {
            // SOURCE_DOCUMENT_DELETED/SOURCE_PERMISSION_CHANGED - 이 Consumer의 대상이
            // 아니다. processed_events에 남길 필요 없다(아무 작업도 건너뛴 것이 없다 -
            // 애초에 이 Consumer의 일이 아니었다).
            return;
        }
        ProcessedEventEntity.Key key = new ProcessedEventEntity.Key(parsed.eventId(), CONSUMER_NAME);
        if (processedEventJpaRepository.existsById(key)) {
            log.debug("duplicate delivery of an already-processed INDEX_REQUESTED event, eventId={}",
                    parsed.eventId());
            return;
        }

        IndexProcessingOutcome outcome = indexOrchestrator.process(parsed.documentId());
        recordProcessed(parsed, outcomeCode(outcome));
    }

    /**
     * M11 후속 교정(2026-09-16, 이번 작업 지시사항 C) - {@code save()}(할당된 ID에 대해
     * 존재 확인 후 저장) 대신 {@link ProcessedEventJpaRepository#insertIfAbsent}(원자적
     * {@code INSERT ... ON CONFLICT DO NOTHING})를 쓴다 - 동시 중복 전달이 있어도 예외
     * 없이 정확히 한쪽만 실제로 삽입된다(그 Method Javadoc 참고). 반환값(1=신규 기록,
     * 0=이미 기록됨)은 둘 다 "지금 이 (eventId, consumer) 조합은 durable하게 기록돼
     * 있다"는 같은 결론이라 구분해서 처리할 필요가 없다.
     */
    private void recordProcessed(ParsedEnvelope parsed, String outcomeCode) {
        ledgerTransaction.executeWithoutResult(status -> processedEventJpaRepository.insertIfAbsent(parsed.eventId(),
                CONSUMER_NAME, outcomeCode, null, parsed.documentId(), clock.instant()));
    }

    private static String outcomeCode(IndexProcessingOutcome outcome) {
        return switch (outcome) {
            case INDEXED -> OUTCOME_INDEXED;
            case SKIPPED_INELIGIBLE -> OUTCOME_SKIPPED_INELIGIBLE;
            case SKIPPED_UNSUPPORTED -> OUTCOME_SKIPPED_UNSUPPORTED;
            case SKIPPED_NO_TEXT -> OUTCOME_SKIPPED_NO_TEXT;
        };
    }

    private ParsedEnvelope parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Map<?, ?> raw;
        try {
            raw = objectMapper.readValue(value, Map.class);
        } catch (RuntimeException malformed) {
            return null;
        }
        String eventType = stringField(raw, "eventType");
        String eventIdRaw = stringField(raw, "eventId");
        String documentIdRaw = stringField(raw, "internalDocumentId");
        if (eventType == null) {
            return null;
        }
        if (!isTriggeringEventType(eventType)) {
            // 다른 Catalog Sync 이벤트(SOURCE_DOCUMENT_DELETED/SOURCE_PERMISSION_CHANGED) -
            // eventId/documentId 형태가 이 Consumer의 기대와 다를 수 있어도(사실 같은
            // Producer가 만드므로 같은 모양이다) 상관없다 - 이 이벤트는 어차피 그대로
            // 무시된다.
            return new ParsedEnvelope(eventType, null, null);
        }
        if (eventIdRaw == null || documentIdRaw == null) {
            return null;
        }
        UUID eventId;
        Long documentId;
        try {
            eventId = UUID.fromString(eventIdRaw);
            documentId = Long.valueOf(documentIdRaw);
        } catch (RuntimeException malformed) {
            return null;
        }
        return new ParsedEnvelope(eventType, eventId, documentId);
    }

    /**
     * M11 후속 교정 - 이 Consumer가 실제로 {@link IndexOrchestrator#process}를
     * 호출하는 두 이벤트 타입. {@link SourceDocumentChangedEvent}를 추가한 것이
     * "통상적인 Content 변경도 재색인을 촉발한다" 교정의 핵심이다(Class Javadoc
     * 참고) - 이 판단 하나만 바꾸면 {@code parse}/{@code onMessage} 양쪽이 일관되게
     * 새 이벤트를 처리한다.
     */
    private static boolean isTriggeringEventType(String eventType) {
        return IndexRequestedEvent.EVENT_TYPE.equals(eventType) || SourceDocumentChangedEvent.EVENT_TYPE.equals(eventType);
    }

    private static String stringField(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        return value instanceof String s ? s : null;
    }

    /**
     * {@code IndexConsumerErrorHandlingConfig}가 DLT 종결 처리 시 재사용하는 최소
     * Best-effort 추출 - 원본 {@link ConsumerRecord} 값에서 {@code documentId}만 다시
     * 뽑아낸다(이미 여러 번 재시도를 거친 뒤이므로 여기서 또 실패해도 안전하게
     * {@code null}을 반환한다 - 그래도 DB 종결 기록/DLT 발행 자체는 계속 진행된다).
     */
    static Long extractDocumentIdBestEffort(String value, ObjectMapper objectMapper) {
        try {
            Map<?, ?> raw = objectMapper.readValue(value, Map.class);
            String documentIdRaw = stringField(raw, "internalDocumentId");
            return documentIdRaw == null ? null : Long.valueOf(documentIdRaw);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private record ParsedEnvelope(String eventType, UUID eventId, Long documentId) {
    }
}
