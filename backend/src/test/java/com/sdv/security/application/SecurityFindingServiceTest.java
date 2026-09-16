package com.sdv.security.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.model.UserContext;
import com.sdv.security.infrastructure.persistence.entity.SecurityFindingEntity;
import com.sdv.security.infrastructure.persistence.repository.SecurityFindingJpaRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class SecurityFindingServiceTest {
    @Test
    void stateUpdateChangesOnlyFindingStateAndWritesContentFreeAudit() {
        SecurityFindingJpaRepository repository = mock(SecurityFindingJpaRepository.class);
        AuditService audit = mock(AuditService.class);
        SecurityFindingEntity finding = mock(SecurityFindingEntity.class);
        when(finding.getId()).thenReturn(4L);
        when(finding.getType()).thenReturn("BROAD_PROVIDER_SHARING_HIGH_CLASSIFICATION");
        when(finding.getSeverity()).thenReturn("HIGH");
        when(finding.getStatus()).thenReturn("OPEN", "ACKNOWLEDGED");
        when(finding.getSourceId()).thenReturn(7L);
        when(finding.getDocumentId()).thenReturn(9L);
        when(finding.getEvidence()).thenReturn(Map.of("classification", "SECRET"));
        when(finding.getDetectedAt()).thenReturn(OffsetDateTime.now());
        when(repository.findPublishedByIdForUpdate(4L)).thenReturn(Optional.of(finding));
        SecurityFindingService service = new SecurityFindingService(repository, audit);

        var result = service.update(new UserContext("admin-a", null, Set.of(), Set.of()), 4L, "ACKNOWLEDGED");

        verify(finding).updateStatus("ACKNOWLEDGED");
        verify(repository).findPublishedByIdForUpdate(4L);
        verify(audit).recordResource(eq("admin-a"), eq("SECURITY_FINDING_STATE_CHANGED"),
                eq("SECURITY_FINDING"), eq("4"), eq("SUCCESS"), eq("OK"), anyMap());
        assertThat(result.documentId()).isEqualTo(9L);
        verifyNoMoreInteractions(repository);
    }
}
