package com.sdv.source.infrastructure.persistence.mapper;

import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import org.springframework.stereotype.Component;

/**
 * F-BE-039. JPA Entity ↔ Source Domain 변환(SRC-002, SRC-004).
 *
 * 공식 패키지는 {@code com.sdv.source.infrastructure.persistence.mapper}이다
 * ({@code com.sdv.source.infrastructure.persistence}가 아님). Entity를 REST로
 * 직접 반환하지 않는다 - 이 Mapper가 그 경계다.
 */
@Component
public class SourcePersistenceMapper {

    public SourceConnection toDomain(SourceConnectionEntity entity) {
        return new SourceConnection(
                entity.getId(),
                SourceType.valueOf(entity.getType()),
                entity.getDisplayName(),
                entity.getOwnerSubject(),
                entity.getStatus(),
                entity.getSyncMode(),
                entity.getTokenRef(),
                entity.getLastSyncAt());
    }

    public SourceConnectionEntity toEntity(SourceConnection domain) {
        return new SourceConnectionEntity(
                domain.getType().name(),
                domain.getDisplayName(),
                domain.getStatus(),
                domain.getSyncMode(),
                domain.getOwnerSubject());
    }
}
