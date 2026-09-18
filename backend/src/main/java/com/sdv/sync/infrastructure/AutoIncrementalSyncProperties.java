package com.sdv.sync.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M17 신규 - {@link AutoIncrementalSyncScheduler}의 설정. 기존
 * {@code @ConfigurationPropertiesScan}({@code SecureDocumentVaultApplication})으로
 * 자동 등록된다({@code com.sdv.event.infrastructure.OutboxPublisherProperties}와
 * 동일한 관례).
 *
 * <p>{@code enabled}는 모든 Profile에서 기본값 {@code false}다({@code
 * application.yml}의 {@code ${SDV_SYNC_AUTO_INCREMENTAL_ENABLED:false}} 자체가
 * 강제한다 - "자동 동기화는 명시적 운영자 설정으로만 활성화" 요구사항을 이 값
 * 하나로 만족시킨다).</p>
 */
@ConfigurationProperties(prefix = "sdv.sync.auto-incremental")
public record AutoIncrementalSyncProperties(
        boolean enabled,
        long pollIntervalMs,
        int batchSize,
        int maxConcurrent,
        long maxBackoffSeconds) {

    public AutoIncrementalSyncProperties {
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 30_000L; // 30초 - 이 작업 지시가 명시한 기본 확인 주기
        }
        if (batchSize <= 0) {
            batchSize = 20;
        }
        if (maxConcurrent <= 0) {
            maxConcurrent = 3;
        }
        if (maxBackoffSeconds <= 0) {
            maxBackoffSeconds = 1800L; // 30분 - 기존 sdv.sync.run.max-duration-ms 기본값과 같은 자릿수
        }
    }
}
