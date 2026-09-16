package com.sdv.security.application;

import com.sdv.security.infrastructure.persistence.entity.SecurityFindingEntity;
import com.sdv.security.infrastructure.persistence.repository.SecurityFindingJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Detects content-free ACL/classification risk; it never changes provider permissions or SDV shares. */
@Service
public class OversharingDetectionService {
    static final String OVERSHARING = "BROAD_PROVIDER_SHARING_HIGH_CLASSIFICATION";
    static final String UNKNOWN = "PROVIDER_PERMISSION_STATE_UNKNOWN";

    private final SourceDocumentJpaRepository documents;
    private final SourcePermissionJpaRepository permissions;
    private final DocumentShareJpaRepository shares;
    private final SecurityFindingJpaRepository findings;

    @Autowired
    public OversharingDetectionService(SourceDocumentJpaRepository documents,
            SourcePermissionJpaRepository permissions, DocumentShareJpaRepository shares,
            SecurityFindingJpaRepository findings) {
        this.documents = documents;
        this.permissions = permissions;
        this.shares = shares;
        this.findings = findings;
    }

    private OversharingDetectionService() {
        documents = null;
        permissions = null;
        shares = null;
        findings = null;
    }

    public static OversharingDetectionService noop() {
        return new OversharingDetectionService();
    }

    /**
     * Re-reads committed state while holding the document row lock. The lock serializes
     * overlapping sync/share triggers; all provider I/O has already finished before this method.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inspect(Long documentId) {
        if (documents == null || documentId == null) {
            return;
        }
        var document = documents.findByIdForUpdate(documentId).orElse(null);
        if (document == null) {
            return;
        }
        var share = shares.findByDocumentIdAndRevokedAtIsNull(documentId).orElse(null);
        if (share == null || !"ACTIVE".equals(document.getState())) {
            clear(OVERSHARING, documentId);
            clear(UNKNOWN, documentId);
            return;
        }
        if (document.getPermissionsUntrustedSince() != null) {
            clear(OVERSHARING, documentId);
            observe(UNKNOWN, "MEDIUM", document.getSourceId(), documentId,
                    Map.of("permissionState", "UNKNOWN"));
            return;
        }

        clear(UNKNOWN, documentId);
        String classification = share.getClassification();
        if (!("SECRET".equals(classification) || "CONFIDENTIAL".equals(classification))) {
            clear(OVERSHARING, documentId);
            return;
        }

        List<String> broadTypes = permissions.findByDocumentId(documentId).stream()
                .map(permission -> permission.getPrincipalType().toUpperCase(Locale.ROOT))
                .filter(type -> "ANYONE".equals(type) || "DOMAIN".equals(type))
                .distinct()
                .toList();
        String principalType = broadTypes.contains("ANYONE") ? "ANYONE"
                : broadTypes.contains("DOMAIN") ? "DOMAIN" : null;
        if (principalType == null) {
            clear(OVERSHARING, documentId);
            return;
        }
        String severity = "SECRET".equals(classification) && "ANYONE".equals(principalType)
                ? "HIGH" : "MEDIUM";
        observe(OVERSHARING, severity, document.getSourceId(), documentId,
                Map.of("classification", classification, "principalType", principalType,
                        "permissionState", "CURRENT"));
    }

    private void observe(String type, String severity, Long sourceId, Long documentId,
            Map<String, String> evidence) {
        List<SecurityFindingEntity> existing = findings.findAllByTypeAndDocumentIdOrderByIdAsc(type, documentId);
        if (existing.isEmpty()) {
            findings.save(new SecurityFindingEntity(type, severity, sourceId, documentId, evidence));
            return;
        }
        existing.getFirst().observe(severity, evidence);
        existing.stream().skip(1).forEach(SecurityFindingEntity::markSuperseded);
    }

    private void clear(String type, Long documentId) {
        findings.findAllByTypeAndDocumentIdOrderByIdAsc(type, documentId)
                .forEach(SecurityFindingEntity::markCleared);
    }
}
