package com.sdv.source.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.exception.NotFoundException;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.mapper.SourcePersistenceMapper;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceOAuthTokenJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceConnectionServiceFileSearchTest {

    @Mock SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Mock SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Mock SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository;
    @Mock SourcePersistenceMapper sourcePersistenceMapper;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher applicationEventPublisher;

    @Test
    void escapesLiteralLikeCharactersAndUsesStableBoundedPaging() {
        SourceConnectionService service = service();
        when(sourceConnectionJpaRepository.findByIdAndOwnerSubject(7L, "owner-a"))
                .thenReturn(Optional.of(new SourceConnectionEntity(
                        "GOOGLE_DRIVE", "A", "ACTIVE", "FULL", "owner-a")));
        when(sourceDocumentJpaRepository.findOwnedForPicker(eq("owner-a"), eq(7L), eq(true),
                eq("%budget\\%\\_2026\\\\final%"), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of()));

        service.listFiles(7L, "owner-a", 2, 25, "  Budget%_2026\\Final  ");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(sourceDocumentJpaRepository).findOwnedForPicker(eq("owner-a"), eq(7L), eq(true),
                eq("%budget\\%\\_2026\\\\final%"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
        assertThat(pageable.getValue().getSort().toString()).isEqualTo("name: ASC,id: ASC");
    }

    @Test
    void verifiesOwnershipBeforeRunningAnyCatalogSearch() {
        SourceConnectionService service = service();
        when(sourceConnectionJpaRepository.findByIdAndOwnerSubject(7L, "not-owner"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listFiles(7L, "not-owner", 0, 50, "report"))
                .isInstanceOf(NotFoundException.class);
        verify(sourceDocumentJpaRepository, never()).findOwnedForPicker(any(), any(), anyBoolean(), any(), any());
    }

    private SourceConnectionService service() {
        return new SourceConnectionService(sourceConnectionJpaRepository, sourceDocumentJpaRepository,
                sourceOAuthTokenJpaRepository, sourcePersistenceMapper, Optional.empty(), auditService,
                applicationEventPublisher);
    }
}
