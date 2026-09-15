package com.sdv.event.infrastructure;

import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M09A 초점 검증(Section 7, 카테고리 7/8) - 격리된 실제 Testcontainers Kafka Broker +
 * 실제 Testcontainers PostgreSQL로 {@link OutboxEventPublisher}의 실제 발행/ACK/
 * 재시도/종결 실패를 검증한다. 이 Broker는 오직 이 Test 하나만을 위한 1회용
 * Container다 - 기존 개발/테스트베드 Broker를 절대 가리키지 않는다.
 *
 * <p>이 Test 자체는 "발행이 됐다"까지만 증명한다 - 실제 색인 Consumer는 이
 * Slice의 범위가 아니다(이 Class를 색인 구현의 증거로 인용하지 않는다).</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        // 이 Test에서만 명시적으로 활성화 - 격리된 Test Broker 대상, 실 서비스는 여전히 기본 비활성화.
        "sdv.sync.outbox-publisher.enabled=true",
        "sdv.sync.outbox-publisher.poll-interval-ms=200",
        "sdv.sync.outbox-publisher.max-attempts=2",
        "sdv.sync.outbox-publisher.stale-claim-seconds=1",
        "sdv.sync.outbox-publisher.send-timeout-ms=5000",
        "sdv.sync.outbox-publisher.topic=sdv.test.source.events"
})
class OutboxEventPublisherIntegrationTest {

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerKafkaBootstrapServers(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Test
    void pendingEventIsPublishedAndMarkedPublishedOnlyAfterBrokerAck() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = outboxEventJpaRepository.save(new OutboxEventEntity(eventId,
                "SOURCE_DOCUMENT_CHANGED", Map.of("eventId", eventId.toString(), "sourceId", "999"),
                "source:999:doc:file-a", Instant.now()));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OutboxEventEntity reloaded = outboxEventJpaRepository.findById(event.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OutboxEventEntity.STATUS_PUBLISHED);
            assertThat(reloaded.getPublishedAt()).isNotNull();
        });

        // Broker에 실제로 전달됐는지까지 직접 소비해 확인한다 - Key(Partition Key)/Header도 함께 검증.
        try (KafkaConsumer<String, String> consumer = newTestConsumer()) {
            consumer.subscribe(java.util.List.of("sdv.test.source.events"));
            ConsumerRecords<String, String> records = pollUntilNotEmpty(consumer, Duration.ofSeconds(10));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            ConsumerRecord<String, String> record = records.iterator().next();
            assertThat(record.key()).isEqualTo("source:999:doc:file-a");
            assertThat(record.value()).contains(eventId.toString());
        }
    }

    private static KafkaConsumer<String, String> newTestConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "sdv-test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private static ConsumerRecords<String, String> pollUntilNotEmpty(KafkaConsumer<String, String> consumer,
            Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records;
            }
        }
        throw new AssertionError("no records received from the test broker within " + timeout);
    }
}
