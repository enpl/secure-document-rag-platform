package com.sdv.sync.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M09A 신규(방치된 Run 복구/경계 있는 Page-Loop 교정) - 한 Sync Run에 허용되는
 * 최대 실행 시간(Lease/Deadline)과 Page 수 상한. 기존
 * {@code @ConfigurationPropertiesScan}로 자동 등록된다.
 *
 * <p>{@code maxDurationMs}는 두 곳에 동시에 쓰인다: (1) {@code
 * SyncRunLifecycle.beginRun}이 새 Run의 {@code lease_expires_at}을 계산할 때,
 * (2) {@code GoogleDriveSyncJob}의 Page-Loop이 그 Deadline을 넘기면 스스로
 * 멈출 때. 두 값을 하나로 통일해, "이 Run을 다른 요청이 방치된 것으로
 * 간주해 회수할 수 있는 시점"과 "이 Run 스스로 멈춰야 하는 시점"이 항상
 * 일치하게 한다 - 별도의 상시 Renewal Scheduler 없이도 건강한 Run은 자신의
 * Lease 안에서 스스로 끝난다.</p>
 */
@ConfigurationProperties(prefix = "sdv.sync.run")
public record SyncRunProperties(long maxDurationMs, int maxPagesPerRun) {

    public SyncRunProperties {
        if (maxDurationMs <= 0) {
            maxDurationMs = 1_800_000L; // 30분
        }
        if (maxPagesPerRun <= 0) {
            maxPagesPerRun = 2000;
        }
    }
}
