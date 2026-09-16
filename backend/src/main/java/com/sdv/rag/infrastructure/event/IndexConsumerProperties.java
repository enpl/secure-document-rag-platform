package com.sdv.rag.infrastructure.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M11 신규 - {@link IndexRequestedConsumer}/{@code IndexConsumerErrorHandlingConfig}의
 * 설정. {@code enabled}는 {@link com.sdv.event.infrastructure.OutboxPublisherProperties}와
 * 동일한 이유로 모든 Profile에서 기본값 {@code false}다(생성자가 한 번 더 강제) - 이
 * Slice는 실제 개인 Drive에 대해 자동으로 색인을 시작하지 않는다. 격리된 Test Kafka
 * Broker를 쓰는 테스트만 이 값을 명시적으로 true로 Override한다.
 */
@ConfigurationProperties(prefix = "sdv.rag.index-consumer")
public record IndexConsumerProperties(boolean enabled, String topic, String groupId, int maxAttempts,
        long backoffMs) {

    public IndexConsumerProperties {
        if (topic == null || topic.isBlank()) {
            topic = "sdv.source.events";
        }
        if (groupId == null || groupId.isBlank()) {
            groupId = "sdv-rag-index-orchestrator";
        }
        if (maxAttempts <= 0) {
            maxAttempts = 4;
        }
        if (backoffMs <= 0) {
            backoffMs = 2000;
        }
    }
}
