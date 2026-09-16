package com.sdv.audit.application;

import com.sdv.common.model.UserContext;
import com.sdv.rag.api.dto.RagAnswerResponse;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;

/** Content-free, allowlisted RAG stage audit records. It never accepts question/evidence/model text. */
@Service
public class RagAuditRecorder {
    private final AuditService audit;

    @Autowired
    public RagAuditRecorder(AuditService audit) {
        this.audit = audit;
    }

    private RagAuditRecorder() {
        this.audit = null;
    }

    public static RagAuditRecorder noop() {
        return new RagAuditRecorder();
    }

    public void stage(UserContext actor, String stage, String result, String reasonCode, Map<String, ?> values) {
        if (audit == null) return;
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : new String[] { "intent", "candidateCount", "verifiedCount", "failedCount",
                "suppliedCount", "citationCount", "selectedCount", "partial", "durationMs" }) {
            Object value = values == null ? null : values.get(key);
            if (value != null) metadata.put(key, bounded(value));
        }
        audit.recordResource(actor.subject(), "RAG_" + stage, "RAG_REQUEST", "ask", result, reasonCode,
                Map.copyOf(metadata));
    }

    public void documentStage(UserContext actor, String stage, Long documentId, String result, String reasonCode,
            Map<String, ?> values) {
        if (audit == null) return;
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : new String[] { "shareGeneration", "connectionGeneration", "sourceVersion", "retry" }) {
            Object value = values == null ? null : values.get(key);
            if (value != null) metadata.put(key, bounded(value));
        }
        audit.recordResource(actor.subject(), "RAG_" + stage, "DOCUMENT", String.valueOf(documentId), result,
                reasonCode, Map.copyOf(metadata));
    }

    public void finalOutcome(UserContext actor, RagAnswerResponse response) {
        stage(actor, "FINAL_OUTCOME", outcome(response.status()), response.reasonCode(), Map.of(
                "citationCount", response.citations().size(), "partial", response.partial()));
    }

    private static String outcome(String status) {
        return switch (status) {
            case "SUCCESS" -> "SUCCESS";
            case "PARTIAL" -> "PARTIAL";
            case "REJECTED" -> "DENIED";
            default -> "FAILURE";
        };
    }

    private static String bounded(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 100 ? text : text.substring(0, 100);
    }
}
