package com.sdv.audit.application;

import com.sdv.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.sdv.audit.infrastructure.persistence.repository.AuditLogJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
public class AuditQueryService {
    private final AuditLogJpaRepository repository;

    public AuditQueryService(AuditLogJpaRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public Page query(String action, String result, String traceId, Instant from, Instant to, int page, int size) {
        if (page < 0 || page > 500 || size < 1 || size > 100 || tooLong(action) || tooLong(result)
                || tooLong(traceId) || (from != null && to != null && from.isAfter(to))) {
            throw new IllegalArgumentException("invalid audit query");
        }
        Specification<AuditLogEntity> spec = (root, query, cb) -> cb.conjunction();
        if (action != null && !action.isBlank()) spec = spec.and((r, q, cb) -> cb.equal(r.get("action"), action));
        if (result != null && !result.isBlank()) spec = spec.and((r, q, cb) -> cb.equal(r.get("result"), result));
        if (traceId != null && !traceId.isBlank()) spec = spec.and((r, q, cb) -> cb.equal(r.get("traceId"), traceId));
        if (from != null) spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("createdAt"), OffsetDateTime.ofInstant(from, ZoneOffset.UTC)));
        if (to != null) spec = spec.and((r, q, cb) -> cb.lessThanOrEqualTo(r.get("createdAt"), OffsetDateTime.ofInstant(to, ZoneOffset.UTC)));
        var resultPage = repository.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new Page(resultPage.getContent().stream().map(AuditItem::from).toList(), page, size,
                resultPage.hasNext());
    }

    private static boolean tooLong(String value) { return value != null && value.length() > 100; }

    public record Page(List<AuditItem> items, int page, int size, boolean hasMore) { }
    public record AuditItem(Long id, String actor, String action, String targetType, String targetId, String result,
            String reasonCode, String traceId, Map<String, String> metadata, OffsetDateTime createdAt) {
        static AuditItem from(AuditLogEntity entity) {
            return new AuditItem(entity.getId(), entity.getActor(), entity.getAction(), entity.getTargetType(),
                    entity.getTargetId(), entity.getResult(), entity.getReasonCode(), entity.getTraceId(),
                    entity.getMetadata() == null ? Map.of() : Map.copyOf(entity.getMetadata()), entity.getCreatedAt());
        }
    }
}
