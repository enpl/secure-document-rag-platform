package com.sdv.source.application;

import com.sdv.audit.application.AuditService;
import com.sdv.event.domain.IndexRequestedEvent;
import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareRestrictionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareRecipientJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareRestrictionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M11 신규 - 순수 단위 테스트(Mockito)로 {@link SourceSharingService}가 이번 작업에서
 * 추가한 "공유 생성/수정/관리자 차단 해제 시 IndexRequestedEvent를 Outbox에 발행하고,
 * unshare/관리자 차단 시 즉시 Embedding을 삭제한다"를 검증한다. HTTP/DB 통합 경로
 * (실제 낙관적 동시성/차단 영속 등)는 기존 {@code SelectiveSharingE2ETest}(Testcontainers)
 * 가 이미 검증한다 - 이 Test는 이번 작업이 새로 추가한 Outbox/Embedding 상호작용만
 * 좁게 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class SourceSharingServiceIndexEventsTest {

    private static final String PUBLISHER = "owner-subject";
    private static final Long SOURCE_ID = 7L;
    private static final Long DOCUMENT_ID = 42L;

    @Mock
    private DocumentShareJpaRepository documentShareJpaRepository;
    @Mock
    private DocumentShareRecipientJpaRepository documentShareRecipientJpaRepository;
    @Mock
    private DocumentShareRestrictionJpaRepository documentShareRestrictionJpaRepository;
    @Mock
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Mock
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Mock
    private OutboxEventJpaRepository outboxEventJpaRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private SourceSharingService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
        service = new SourceSharingService(documentShareJpaRepository, documentShareRecipientJpaRepository,
                documentShareRestrictionJpaRepository, sourceDocumentJpaRepository, sourceConnectionJpaRepository,
                outboxEventJpaRepository, auditService, applicationEventPublisher, fixedClock);
    }

    @Test
    void createSharePublishesAnIndexRequestedEventForAFreshUnblockedShare() {
        givenOwnedActiveDocumentAndConnection();
        when(documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(DOCUMENT_ID)).thenReturn(Optional.empty());
        when(documentShareRestrictionJpaRepository.findBySourceIdAndDocumentId(SOURCE_ID, DOCUMENT_ID))
                .thenReturn(Optional.empty());
        when(documentShareJpaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createShare(PUBLISHER, SOURCE_ID, DOCUMENT_ID, "INTERNAL", Set.of("VIEW"), Set.of("recipient-b"));

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(IndexRequestedEvent.EVENT_TYPE);
    }

    @Test
    void createShareDoesNotPublishWhenTheShareIsBornAdminBlockedByAnExistingRestriction() {
        givenOwnedActiveDocumentAndConnection();
        when(documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(DOCUMENT_ID)).thenReturn(Optional.empty());
        DocumentShareRestrictionEntity blockedRestriction = new DocumentShareRestrictionEntity(SOURCE_ID, DOCUMENT_ID,
                "admin-subject", Instant.now());
        blockedRestriction.applyBlock("admin-subject", "policy violation", Instant.now());
        when(documentShareRestrictionJpaRepository.findBySourceIdAndDocumentId(SOURCE_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(blockedRestriction));
        when(documentShareJpaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createShare(PUBLISHER, SOURCE_ID, DOCUMENT_ID, "INTERNAL", Set.of("VIEW"), Set.of("recipient-b"));

        verify(outboxEventJpaRepository, never()).save(any());
    }

    @Test
    void updateShareRepublishesAnIndexRequestedEventForAnUnblockedShare() {
        DocumentShareEntity share = activeShare();
        when(documentShareJpaRepository.findByIdAndPublisherSubject(1L, PUBLISHER)).thenReturn(Optional.of(share));
        when(sourceDocumentJpaRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(activeDocument()));

        service.updateShare(PUBLISHER, 1L, share.getGeneration(), "CONFIDENTIAL", Set.of("VIEW"),
                Set.of("recipient-c"));

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(IndexRequestedEvent.EVENT_TYPE);
    }

    @Test
    void unshareLocksTheDocumentRowAndImmediatelyDeletesItsEmbeddings() {
        DocumentShareEntity share = activeShare();
        when(documentShareJpaRepository.findByIdAndPublisherSubject(1L, PUBLISHER)).thenReturn(Optional.of(share));

        service.unshare(PUBLISHER, 1L);

        verify(sourceDocumentJpaRepository).findByIdForUpdate(DOCUMENT_ID);
        verify(sourceDocumentJpaRepository).deleteEmbeddingIndexForDocument(DOCUMENT_ID);
        verify(outboxEventJpaRepository, never()).save(any());
    }

    @Test
    void adminBlockingAnActiveShareImmediatelyDeletesItsEmbeddingsWithoutPublishing() {
        DocumentShareEntity share = activeShare();
        when(documentShareJpaRepository.findByIdAndRevokedAtIsNull(1L)).thenReturn(Optional.of(share));
        when(documentShareRestrictionJpaRepository.findBySourceIdAndDocumentId(SOURCE_ID, DOCUMENT_ID))
                .thenReturn(Optional.empty());
        when(documentShareJpaRepository.findRecipientSubjects(1L)).thenReturn(java.util.List.of("recipient-b"));

        service.adminSetBlocked("admin-subject", 1L, true, "policy violation");

        verify(sourceDocumentJpaRepository).deleteEmbeddingIndexForDocument(DOCUMENT_ID);
        verify(outboxEventJpaRepository, never()).save(any());
    }

    @Test
    void adminUnblockingAPreviouslyBlockedSharePublishesAFreshIndexRequestedEvent() {
        DocumentShareEntity share = activeShare();
        share.applyAdminBlock(true, "policy violation", Instant.now());
        when(documentShareJpaRepository.findByIdAndRevokedAtIsNull(1L)).thenReturn(Optional.of(share));
        when(documentShareRestrictionJpaRepository.findBySourceIdAndDocumentId(SOURCE_ID, DOCUMENT_ID))
                .thenReturn(Optional.empty());
        when(sourceDocumentJpaRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(activeDocument()));
        when(documentShareJpaRepository.findRecipientSubjects(1L)).thenReturn(java.util.List.of("recipient-b"));

        service.adminSetBlocked("admin-subject", 1L, false, null);

        verify(sourceDocumentJpaRepository, never()).deleteEmbeddingIndexForDocument(any());
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(IndexRequestedEvent.EVENT_TYPE);
    }

    private void givenOwnedActiveDocumentAndConnection() {
        when(sourceDocumentJpaRepository.findByIdAndOwnerSubject(DOCUMENT_ID, PUBLISHER))
                .thenReturn(Optional.of(activeDocument()));
        when(sourceConnectionJpaRepository.findByIdAndOwnerSubject(SOURCE_ID, PUBLISHER))
                .thenReturn(Optional.of(activeConnection()));
    }

    private static SourceDocumentEntity activeDocument() {
        SourceDocumentEntity document = new SourceDocumentEntity(SOURCE_ID, "ext-doc-1", "Doc.pdf", "application/pdf",
                "v1", null, "ACTIVE", "PENDING", null);
        // @GeneratedValue 필드라 Setter가 없다 - 실제 영속화 없이(Testcontainers를 쓰지
        // 않는 순수 단위 Test) createShare/updateShare가 요구하는 non-null documentId를
        // 만들기 위한 최소한의 우회다(다른 필드는 전혀 건드리지 않는다).
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        return document;
    }

    private static SourceConnectionEntity activeConnection() {
        return new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL", PUBLISHER);
    }

    private static DocumentShareEntity activeShare() {
        return new DocumentShareEntity(PUBLISHER, SOURCE_ID, DOCUMENT_ID, "INTERNAL", "VIEW", Instant.now());
    }
}
