package com.sdv.event.infrastructure;

import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * F-BE-074 (M09A 신규). {@code outbox_events}(PENDING) 행을 Kafka로 발행하는
 * Publisher(SYN-005) - Transactional Outbox 패턴의 소비(발행) 쪽 절반이다.
 *
 * <h2>기본 비활성화</h2>
 * <p>{@code sdv.sync.outbox-publisher.enabled}가 명시적으로 {@code true}일
 * 때만 이 Bean 자체가 등록된다({@link ConditionalOnProperty}) - 모든
 * Profile(local/compose/testbed)에서 기본값은 {@code false}다(추가로
 * {@link OutboxPublisherProperties} 생성자가 한 번 더 강제). 현재 실제
 * {@code IndexRequestedConsumer}가 존재하지 않으므로({@code
 * docs/plan/SDV_MVP_DEFERRED.md} 참고), 이 Publisher를 켜서 실제 개인 Drive에
 * 대해 발행을 시작하는 것은 이 Slice의 범위 밖이다 - 격리된 Test Kafka
 * Broker를 쓰는 테스트만 이 값을 Override한다.</p>
 *
 * <h2>Claim과 Broker 호출을 분리한 이유</h2>
 * <p>{@link OutboxPublishWriter#claimBatch}는 짧은 DB Transaction 안에서만
 * 실행된다(별도 Bean - Spring self-invocation 함정을 피하기 위해서다. 이
 * Class의 {@code @Scheduled} 메서드가 자기 자신의 {@code @Transactional}
 * 메서드를 직접 부르면 Proxy를 거치지 않아 Transaction이 걸리지 않는다,
 * {@code com.sdv.sync.application.SourceSyncPageWriter} Class Javadoc 참고) -
 * {@code FOR UPDATE SKIP LOCKED}(여러 Publisher Instance가 같은 행을 동시에
 * Claim하지 않게 함)로 배치를 골라 {@code PUBLISHING}으로 표시하고 즉시
 * Commit한다. 실제 Kafka 전송({@link KafkaTemplate#send})은 그 Transaction
 * 밖에서, 어떤 DB Row Lock도 쥐지 않은 채 일어난다(MVP-22가 기록한 "Network
 * 호출 동안 Lock 보유" 실수를 반복하지 않는다). Publisher Instance가 Claim
 * 이후 Broker 응답 전에 죽으면, 그 행은 {@code claimed_at}이 오래된
 * {@code PUBLISHING} 상태로 남는다 - {@link OutboxPublishWriter#recoverStaleClaims}
 * 가 매 실행마다 그런 행을 {@code PENDING}으로 되돌려 다른 Instance가 다시
 * Claim할 수 있게 한다.</p>
 *
 * <h2>At-Least-Once - 반드시 알아야 할 한계</h2>
 * <p>{@link KafkaTemplate#send}가 Broker ACK을 받은 뒤에만 {@code PUBLISHED}로
 * 표시한다({@link OutboxPublishWriter#markPublished}) - 그러나 ACK을 받은
 * 직후, {@code PUBLISHED}로 표시하는 DB Update 자체가 실패(예: DB 장애/
 * Instance Crash)하면 그 행은 {@code PUBLISHING}으로 남아 {@link
 * OutboxPublishWriter#recoverStaleClaims}에 의해 나중에 다시 Claim/재전송된다
 * - 즉 **같은 {@code event_id}가 Kafka에 두 번 이상 전달될 수 있다
 * (At-Least-Once, Exactly-Once가 아니다)**. 이는 숨기지 않고 명시적으로
 * 기록한다 - 향후 실제 Consumer(M11 {@code IndexRequestedConsumer})는 반드시
 * {@code event_id} 기준의 자체 멱등성 처리({@code processed_events})로 중복
 * 수신을 흡수해야 한다(이 Slice가 구현하지 않는 부분 - Deferred Register 참고).</p>
 */
@Component
@ConditionalOnProperty(prefix = "sdv.sync.outbox-publisher", name = "enabled", havingValue = "true")
public class OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final OutboxPublishWriter writer;
    // Object,Object - Spring Boot의 KafkaAutoConfiguration이 만드는 기본 KafkaTemplate Bean의
    // 실제 선언 Generic 타입과 일치시킨다(KafkaTemplate<String,String>으로 선언하면 Generic
    // 불변성 때문에 Bean을 찾지 못한다) - 실제 Key/Value 직렬화는 spring.kafka.producer.
    // key-serializer/value-serializer(application.yml, StringSerializer)가 결정한다.
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxPublisherProperties properties;

    @Autowired
    public OutboxEventPublisher(OutboxPublishWriter writer, KafkaTemplate<Object, Object> kafkaTemplate,
            ObjectMapper objectMapper, OutboxPublisherProperties properties) {
        this.writer = writer;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${sdv.sync.outbox-publisher.poll-interval-ms:5000}")
    public void publishPendingBatch() {
        writer.recoverStaleClaims(properties.staleClaimSeconds());
        List<OutboxEventEntity> claimed = writer.claimBatch(properties.batchSize());
        for (OutboxEventEntity event : claimed) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEventEntity event) {
        String value;
        try {
            value = objectMapper.writeValueAsString(event.getPayload());
        } catch (RuntimeException serializationFailure) {
            // Payload는 이미 Map<String,String>(DomainEvent#toPayload 계약)뿐이라 정상 경로에서는
            // 사실상 발생하지 않는다 - 그래도 Broker를 부르지 않고 곧장 안전한 실패 경로로 보낸다.
            handleFailure(event, "payload serialization failed");
            return;
        }
        try {
            // Broker ACK을 받을 때까지 기다린다 - ACK 전에는 절대 PUBLISHED로 표시하지 않는다.
            // eventId/eventType은 이미 JSON Payload 자체에 포함돼 있다(DomainEvent#toPayload) -
            // 별도 Kafka Header가 없어도 Consumer가 Body만으로 식별할 수 있다.
            kafkaTemplate.send(properties.topic(), event.getPartitionKey(), value)
                    .get(properties.sendTimeoutMs(), TimeUnit.MILLISECONDS);
            writer.markPublished(event.getId(), event.getClaimToken());
        } catch (Exception sendFailure) {
            // 원본 예외 메시지/Stack Trace는 절대 저장/로그하지 않는다(GlobalExceptionHandler와 동일한 원칙) -
            // 안전한 분류(Class 이름)만 남긴다.
            handleFailure(event, "kafka send failed: " + sendFailure.getClass().getSimpleName());
        }
    }

    private void handleFailure(OutboxEventEntity event, String safeError) {
        boolean terminal = writer.handleFailure(event, safeError, properties.maxAttempts());
        if (terminal) {
            log.warn("outbox event terminally failed after {} attempts, eventId={}", properties.maxAttempts(),
                    event.getEventId());
        }
    }
}
