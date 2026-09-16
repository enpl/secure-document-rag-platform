package com.sdv.security.application;

import com.sdv.security.infrastructure.persistence.entity.SecurityFindingEntity;
import com.sdv.security.infrastructure.persistence.repository.SecurityFindingJpaRepository;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OversharingDetectionServiceTest {
    @Test
    void secretAnyoneCreatesOneHighContentFreeFindingWithoutChangingProviderPermission() {
        Fixture f = new Fixture("SECRET");
        when(f.permissions.findByDocumentId(9L)).thenReturn(List.of(
                new SourcePermissionEntity(9L, "anyone", "", "READ", Instant.now())));
        SecurityFindingEntity existing = new SecurityFindingEntity(OversharingDetectionService.OVERSHARING, "HIGH",
                7L, 9L, Map.of("classification", "SECRET", "principalType", "ANYONE",
                        "permissionState", "CURRENT"));
        when(f.findings.findAllByTypeAndDocumentIdOrderByIdAsc(OversharingDetectionService.OVERSHARING, 9L))
                .thenReturn(List.of(), List.of(existing));

        f.service.inspect(9L);
        f.service.inspect(9L);

        ArgumentCaptor<SecurityFindingEntity> saved = ArgumentCaptor.forClass(SecurityFindingEntity.class);
        verify(f.findings).save(saved.capture());
        assertThat(saved.getValue().getSeverity()).isEqualTo("HIGH");
        assertThat(saved.getValue().getEvidence()).containsEntry("classification", "SECRET")
                .doesNotContainValue("private-name.pdf").doesNotContainValue("provider-id");
        verify(f.permissions, times(2)).findByDocumentId(9L);
        verify(f.permissions, never()).save(any());
        verify(f.permissions, never()).deleteByDocumentId(anyLong());
    }

    @Test
    void stalePermissionEvidenceIsReportedAsUnknownInsteadOfOversharingFact() {
        Fixture f = new Fixture("SECRET", true);
        when(f.findings.findAllByTypeAndDocumentIdOrderByIdAsc(anyString(), eq(9L))).thenReturn(List.of());

        f.service.inspect(9L);

        ArgumentCaptor<SecurityFindingEntity> saved = ArgumentCaptor.forClass(SecurityFindingEntity.class);
        verify(f.findings).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(OversharingDetectionService.UNKNOWN);
        assertThat(saved.getValue().getEvidence()).containsEntry("permissionState", "UNKNOWN");
        verifyNoInteractions(f.permissions);
    }

    @Test
    void anyoneAclDominatesDomainRegardlessOfRepositoryOrdering() {
        Fixture f = new Fixture("SECRET");
        when(f.permissions.findByDocumentId(9L)).thenReturn(List.of(
                new SourcePermissionEntity(9L, "domain", "example.com", "READ", Instant.now()),
                new SourcePermissionEntity(9L, "anyone", "", "READ", Instant.now())));
        when(f.findings.findAllByTypeAndDocumentIdOrderByIdAsc(anyString(), eq(9L))).thenReturn(List.of());

        f.service.inspect(9L);

        ArgumentCaptor<SecurityFindingEntity> saved = ArgumentCaptor.forClass(SecurityFindingEntity.class);
        verify(f.findings).save(saved.capture());
        assertThat(saved.getValue().getSeverity()).isEqualTo("HIGH");
        assertThat(saved.getValue().getEvidence()).containsEntry("principalType", "ANYONE");
    }

    @Test
    void materiallyChangedRiskUpdatesAndReopensButIdenticalObservationPreservesAdminState() {
        Fixture f = new Fixture("SECRET");
        SecurityFindingEntity finding = new SecurityFindingEntity(OversharingDetectionService.OVERSHARING,
                "MEDIUM", 7L, 9L, Map.of("classification", "CONFIDENTIAL", "principalType", "DOMAIN",
                        "permissionState", "CURRENT"));
        finding.updateStatus("ACKNOWLEDGED");
        when(f.permissions.findByDocumentId(9L)).thenReturn(List.of(
                new SourcePermissionEntity(9L, "anyone", "", "READ", Instant.now())));
        when(f.findings.findAllByTypeAndDocumentIdOrderByIdAsc(OversharingDetectionService.UNKNOWN, 9L))
                .thenReturn(List.of());
        when(f.findings.findAllByTypeAndDocumentIdOrderByIdAsc(OversharingDetectionService.OVERSHARING, 9L))
                .thenReturn(List.of(finding));

        f.service.inspect(9L);
        assertThat(finding.getSeverity()).isEqualTo("HIGH");
        assertThat(finding.getStatus()).isEqualTo("OPEN");

        finding.updateStatus("ACKNOWLEDGED");
        f.service.inspect(9L);
        assertThat(finding.getStatus()).as("an unchanged observation must not erase ADMIN acknowledgement")
                .isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void clearedRiskThatRecursBecomesVisibleAgain() {
        Fixture f = new Fixture("SECRET");
        SecurityFindingEntity finding = new SecurityFindingEntity(OversharingDetectionService.OVERSHARING,
                "HIGH", 7L, 9L, Map.of("classification", "SECRET", "principalType", "ANYONE",
                        "permissionState", "CURRENT"));
        when(f.permissions.findByDocumentId(9L)).thenReturn(List.of(), List.of(
                new SourcePermissionEntity(9L, "anyone", "", "READ", Instant.now())));
        when(f.findings.findAllByTypeAndDocumentIdOrderByIdAsc(OversharingDetectionService.UNKNOWN, 9L))
                .thenReturn(List.of());
        when(f.findings.findAllByTypeAndDocumentIdOrderByIdAsc(OversharingDetectionService.OVERSHARING, 9L))
                .thenReturn(List.of(finding));

        f.service.inspect(9L);
        assertThat(finding.getEvidence()).containsEntry("observationState", "CLEARED");
        assertThat(finding.getStatus()).isEqualTo("RESOLVED");

        f.service.inspect(9L);
        assertThat(finding.getEvidence()).containsEntry("observationState", "ACTIVE");
        assertThat(finding.getStatus()).isEqualTo("OPEN");
    }

    private static final class Fixture {
        final SourceDocumentJpaRepository documents = mock(SourceDocumentJpaRepository.class);
        final SourcePermissionJpaRepository permissions = mock(SourcePermissionJpaRepository.class);
        final DocumentShareJpaRepository shares = mock(DocumentShareJpaRepository.class);
        final SecurityFindingJpaRepository findings = mock(SecurityFindingJpaRepository.class);
        final OversharingDetectionService service;

        Fixture(String classification) { this(classification, false); }

        Fixture(String classification, boolean untrusted) {
            SourceDocumentEntity document = new SourceDocumentEntity(7L, "provider-id", "private-name.pdf",
                    "application/pdf", "v1", Instant.now(), "ACTIVE", "INDEXED", null);
            if (untrusted) document.markPermissionsUntrusted(Instant.now());
            when(documents.findByIdForUpdate(9L)).thenReturn(Optional.of(document));
            when(shares.findByDocumentIdAndRevokedAtIsNull(9L)).thenReturn(Optional.of(
                    new DocumentShareEntity("owner", 7L, 9L, classification, "VIEW", Instant.now())));
            service = new OversharingDetectionService(documents, permissions, shares, findings);
        }
    }
}
