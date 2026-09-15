package com.sdv.sync.api.dto;

import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;

import java.time.Instant;

/**
 * F-BE-062 (M09A 신규) - {@code POST /api/admin/sources/{id}/sync} 응답. JPA
 * Entity({@link SyncRunEntity})를 직접 반환하지 않는다(API DTO/Persistence
 * Entity 분리 원칙).
 */
public record SyncRunResponse(Long runId, Long sourceId, String mode, String status, int total, int success,
        int failed, Instant startedAt, Instant endedAt) {

    public static SyncRunResponse from(SyncRunEntity run) {
        return new SyncRunResponse(run.getId(), run.getSourceId(), run.getMode(), run.getStatus(), run.getTotal(),
                run.getSuccess(), run.getFailed(), run.getStartedAt(), run.getEndedAt());
    }
}
