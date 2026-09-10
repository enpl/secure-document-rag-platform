package com.sdv.source.api.mapper;

import com.sdv.source.api.dto.CreateSourceRequest;
import com.sdv.source.api.dto.SourceResponse;
import com.sdv.source.domain.SourceConnection;
import org.springframework.stereotype.Component;

/**
 * F-BE-030. API DTO ↔ Domain 변환. Persistence Mapper
 * ({@link com.sdv.source.infrastructure.persistence.mapper.SourcePersistenceMapper})와는
 * 별개 경계다 - 병합하지 않는다.
 *
 * <p>{@link #toDomain}은 {@code ownerSubject}를 별도 파라미터로만 받는다 -
 * {@link CreateSourceRequest}는 소유자 필드를 갖지 않으므로, 구조적으로
 * Request에서 소유자를 가져올 수 없다.</p>
 */
@Component
public class SourceApiMapper {

    public SourceConnection toDomain(CreateSourceRequest request, String ownerSubject) {
        return new SourceConnection(
                null,
                request.type(),
                request.name(),
                ownerSubject,
                SourceConnection.STATUS_ACTIVE,
                request.syncMode(),
                null,
                null);
    }

    public SourceResponse toResponse(SourceConnection connection) {
        return new SourceResponse(
                connection.getId(),
                connection.getType(),
                connection.getStatus(),
                connection.getLastSyncAt());
    }
}
