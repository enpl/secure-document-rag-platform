package com.sdv.security.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.model.UserContext;
import com.sdv.security.infrastructure.persistence.entity.SecurityFindingEntity;
import com.sdv.security.infrastructure.persistence.repository.SecurityFindingJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SecurityFindingService {
    private static final Set<String> STATES = Set.of("OPEN", "ACKNOWLEDGED", "RESOLVED");
    private final SecurityFindingJpaRepository repository;
    private final AuditService audit;
    public SecurityFindingService(SecurityFindingJpaRepository repository, AuditService audit) {
        this.repository = repository; this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page list(String status, int page, int size) {
        if (page < 0 || page > 500 || size < 1 || size > 100 || (status != null && !STATES.contains(status)))
            throw new IllegalArgumentException("invalid finding query");
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("detectedAt"), Sort.Order.desc("id")));
        var result = status == null ? repository.findPublished(pageable)
                : repository.findPublishedByStatus(status, pageable);
        return new Page(result.getContent().stream().map(Item::from).toList(), page, size, result.hasNext());
    }

    @Transactional
    public Item update(UserContext actor, Long id, String status) {
        if (id == null || id <= 0 || !STATES.contains(status)) throw new IllegalArgumentException("invalid finding update");
        var finding = repository.findPublishedByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("finding not found"));
        String previous = finding.getStatus();
        finding.updateStatus(status);
        audit.recordResource(actor.subject(), "SECURITY_FINDING_STATE_CHANGED", "SECURITY_FINDING", String.valueOf(id),
                "SUCCESS", "OK", Map.of("previousStatus", previous, "newStatus", status,
                        "findingType", finding.getType()));
        return Item.from(finding);
    }

    public record Page(List<Item> items, int page, int size, boolean hasMore) { }
    public record Item(Long id, String type, String severity, String status, Long sourceId, Long documentId,
            Map<String, String> evidence, OffsetDateTime detectedAt) {
        static Item from(SecurityFindingEntity entity) {
            return new Item(entity.getId(), entity.getType(), entity.getSeverity(), entity.getStatus(),
                    entity.getSourceId(), entity.getDocumentId(), Map.copyOf(entity.getEvidence()), entity.getDetectedAt());
        }
    }
}
