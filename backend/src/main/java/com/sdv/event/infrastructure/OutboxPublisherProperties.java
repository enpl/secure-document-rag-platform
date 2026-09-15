package com.sdv.event.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * F-BE-074 (M09A 신규) - {@link OutboxEventPublisher}의 설정. 기존
 * {@code @ConfigurationPropertiesScan}({@code SecureDocumentVaultApplication})
 * 로 자동 등록된다.
 *
 * <p>{@code enabled}는 모든 Profile에서 기본값 {@code false}다(생성자에서
 * 강제) - "자동 Sync/Outbox 발행은 어떤 Profile에서도 기본 비활성화" 요구사항을
 * 이 값 하나로 만족시킨다. 실제로 켜려면 명시적으로
 * {@code sdv.sync.outbox-publisher.enabled=true}를 설정해야 한다(테스트의
 * 격리된 Kafka Broker 대상에서만).</p>
 */
@ConfigurationProperties(prefix = "sdv.sync.outbox-publisher")
public record OutboxPublisherProperties(
        boolean enabled,
        int batchSize,
        int maxAttempts,
        long pollIntervalMs,
        long sendTimeoutMs,
        long staleClaimSeconds,
        String topic) {

    public OutboxPublisherProperties {
        if (batchSize <= 0) {
            batchSize = 50;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 8;
        }
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 5000;
        }
        if (sendTimeoutMs <= 0) {
            sendTimeoutMs = 10000;
        }
        if (staleClaimSeconds <= 0) {
            // M09A 교정 - 기본 batchSize(50) x sendTimeoutMs(10s) = 최대 500초까지 걸릴 수 있는
            // 순차 배치보다 여유 있게 크게 잡는다(V009 Claim Token Fencing이 이제 정확성을
            // 보장하므로, 이 값은 순수하게 "얼마나 빨리 죽은 Publisher를 회수할지"의 튜닝일 뿐이다).
            staleClaimSeconds = 600;
        }
        if (topic == null || topic.isBlank()) {
            topic = "sdv.source.events";
        }
    }
}
