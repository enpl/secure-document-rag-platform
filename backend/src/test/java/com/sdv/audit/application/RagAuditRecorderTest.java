package com.sdv.audit.application;

import com.sdv.audit.application.port.AuditEventPort;
import com.sdv.audit.domain.AuditEvent;
import com.sdv.common.model.UserContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RagAuditRecorderTest {
    @Test
    void persistsOnlyAllowlistedBoundedMetadataWithoutContentCanaries() {
        AuditEventPort port = mock(AuditEventPort.class);
        RagAuditRecorder recorder = new RagAuditRecorder(new AuditService(port));
        UserContext user = new UserContext("user-b", null, Set.of(), Set.of());

        recorder.stage(user, "MODEL_USE", "SUCCESS", "OK", Map.of(
                "suppliedCount", 2, "question", "QUESTION_CANARY", "evidence", "EVIDENCE_CANARY"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(port).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.targetType()).isEqualTo("RAG_REQUEST");
        assertThat(event.metadata()).containsEntry("suppliedCount", "2");
        assertThat(event.metadata().toString()).doesNotContain("QUESTION_CANARY", "EVIDENCE_CANARY");
    }
}
