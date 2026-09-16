package com.sdv.rag.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M12 신규(F-BE-206/208, §2A.6) - {@code EncryptedEphemeralEvidenceStore}의 Hard
 * TTL/용량 상한. {@code ttlSeconds}는 배포 환경에서 300보다 짧게 설정하는 것만
 * 허용된다(CORE_SPEC "Hard TTL은 원본 생성 시점부터 최대 300초다 - 배포 환경에서
 * 더 짧게 설정하는 것은 허용된다") - 이 값보다 큰 설정은 조용히 300으로 낮춘다
 * (Fail Closed, 명세를 넘는 값을 신뢰하지 않는다).
 */
@ConfigurationProperties(prefix = "sdv.rag.ephemeral-evidence")
public record EphemeralEvidenceProperties(long ttlSeconds, int maxEntries, long maxBytes) {

    /** CORE_SPEC §2A.6 - 어떤 설정으로도 이 값을 넘을 수 없다. */
    public static final long HARD_MAX_TTL_SECONDS = 300L;

    public EphemeralEvidenceProperties {
        if (ttlSeconds <= 0 || ttlSeconds > HARD_MAX_TTL_SECONDS) {
            ttlSeconds = HARD_MAX_TTL_SECONDS;
        }
        if (maxEntries <= 0) {
            maxEntries = 5_000;
        }
        if (maxBytes <= 0) {
            maxBytes = 16L * 1024 * 1024;
        }
    }
}
