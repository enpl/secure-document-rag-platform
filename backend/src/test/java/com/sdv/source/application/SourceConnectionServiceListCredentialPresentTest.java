package com.sdv.source.application;

import com.sdv.audit.application.AuditService;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.mapper.SourcePersistenceMapper;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceOAuthTokenJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MVP-17({@code docs/plan/SDV_MVP_DEFERRED.md}) - 순수 단위 테스트(Mockito, Spring
 * Context 없음). {@link SourceConnectionService#list} 가 {@code credentialPresent}를
 * 정확히 계산하고, 그 계산이 {@link SourceOAuthTokenJpaRepository#existsBySourceId}
 * (부작용 없음)만 사용할 뿐 {@link com.sdv.source.application.port.SourceTokenStore}는
 * 절대 건드리지 않음을 증명한다 - {@code SourceTokenStore.load()}는 실제로 Google
 * Refresh Call/재암호화 DB 쓰기를 유발할 수 있으므로, 단순 목록 조회가 이를 호출하면
 * 안 된다.
 */
@ExtendWith(MockitoExtension.class)
class SourceConnectionServiceListCredentialPresentTest {

    @Mock
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Mock
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Mock
    private SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository;
    @Mock
    private SourcePersistenceMapper sourcePersistenceMapper;
    @Mock
    private com.sdv.source.application.port.SourceTokenStore sourceTokenStore;
    @Mock
    private AuditService auditService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    void listDistinguishesConnectedFromNotYetConnectedWithoutTouchingTheTokenStore() {
        SourceConnectionEntity withCredential = new SourceConnectionEntity("GOOGLE_DRIVE", "With Credential",
                "ACTIVE", "FULL", "owner-1");
        SourceConnectionEntity withoutCredential = new SourceConnectionEntity("GOOGLE_DRIVE", "Without Credential",
                "ACTIVE", "FULL", "owner-1");
        setId(withCredential, 1L);
        setId(withoutCredential, 2L);

        SourceConnection withCredentialDomain = new SourceConnection(1L, com.sdv.source.domain.SourceType.GOOGLE_DRIVE,
                "With Credential", "owner-1", "ACTIVE", "FULL", "some-token-ref", null);
        SourceConnection withoutCredentialDomain = new SourceConnection(2L,
                com.sdv.source.domain.SourceType.GOOGLE_DRIVE, "Without Credential", "owner-1", "ACTIVE", "FULL",
                null, null);

        when(sourceConnectionJpaRepository.findAllByOwnerSubject("owner-1"))
                .thenReturn(List.of(withCredential, withoutCredential));
        when(sourcePersistenceMapper.toDomain(withCredential)).thenReturn(withCredentialDomain);
        when(sourcePersistenceMapper.toDomain(withoutCredential)).thenReturn(withoutCredentialDomain);
        when(sourceOAuthTokenJpaRepository.existsBySourceId(1L)).thenReturn(true);
        when(sourceOAuthTokenJpaRepository.existsBySourceId(2L)).thenReturn(false);

        SourceConnectionService service = new SourceConnectionService(sourceConnectionJpaRepository,
                sourceDocumentJpaRepository, sourceOAuthTokenJpaRepository, sourcePersistenceMapper,
                Optional.of(sourceTokenStore), auditService, applicationEventPublisher);

        List<SourceConnectionService.SourceListItem> result = service.list("owner-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).connection().getId()).isEqualTo(1L);
        assertThat(result.get(0).credentialPresent()).isTrue();
        assertThat(result.get(1).connection().getId()).isEqualTo(2L);
        assertThat(result.get(1).credentialPresent()).isFalse();

        // 부작용 있는 SourceTokenStore(load/save/delete)는 목록 조회 중 절대 호출되지 않는다.
        verifyNoInteractions(sourceTokenStore);
    }

    private static void setId(SourceConnectionEntity entity, Long id) {
        try {
            var field = SourceConnectionEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
