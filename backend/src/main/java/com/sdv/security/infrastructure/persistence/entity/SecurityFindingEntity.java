package com.sdv.security.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "security_findings")
public class SecurityFindingEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 100) private String type;
    @Column(nullable = false, length = 30) private String severity;
    @Column(nullable = false, length = 30) private String status;
    @Column(name = "source_id") private Long sourceId;
    @Column(name = "document_id") private Long documentId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private Map<String, String> evidence;
    @Column(name = "detected_at", insertable = false, updatable = false) private OffsetDateTime detectedAt;

    protected SecurityFindingEntity() { }
    public SecurityFindingEntity(String type, String severity, Long sourceId, Long documentId,
            Map<String, String> evidence) {
        this.type = type; this.severity = severity; this.status = "OPEN"; this.sourceId = sourceId;
        this.documentId = documentId; this.evidence = activeEvidence(evidence);
    }
    public void updateStatus(String status) { this.status = status; }
    public void observe(String severity, Map<String, String> evidence) {
        Map<String, String> next = activeEvidence(evidence);
        if (this.severity.equals(severity) && next.equals(this.evidence)) return;
        this.severity = severity;
        this.evidence = next;
        this.status = "OPEN";
    }
    public void markCleared() {
        if ("CLEARED".equals(observationState()) || "SUPERSEDED".equals(observationState())) return;
        this.evidence = Map.of("observationState", "CLEARED");
        this.status = "RESOLVED";
    }
    public void markSuperseded() {
        this.evidence = Map.of("observationState", "SUPERSEDED");
        this.status = "RESOLVED";
    }
    private String observationState() { return evidence == null ? null : evidence.get("observationState"); }
    private static Map<String, String> activeEvidence(Map<String, String> evidence) {
        LinkedHashMap<String, String> bounded = new LinkedHashMap<>(evidence);
        bounded.put("observationState", "ACTIVE");
        return Map.copyOf(bounded);
    }
    public Long getId() { return id; }
    public String getType() { return type; }
    public String getSeverity() { return severity; }
    public String getStatus() { return status; }
    public Long getSourceId() { return sourceId; }
    public Long getDocumentId() { return documentId; }
    public Map<String, String> getEvidence() { return evidence == null ? Map.of() : evidence; }
    public OffsetDateTime getDetectedAt() { return detectedAt; }
}
