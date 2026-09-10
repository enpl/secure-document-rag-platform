package com.sdv.source.application;

import com.sdv.audit.application.port.AuditEventPort;
import com.sdv.audit.domain.AuditEvent;
import com.sdv.audit.infrastructure.persistence.AuditLogPersistenceAdapter;
import com.sdv.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.sdv.audit.infrastructure.persistence.repository.AuditLogJpaRepository;
import com.sdv.common.exception.NotFoundException;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M04 후속 교정 검증(항목 1): Source DB 변경과 그 성공 감사(Audit) 기록은
 * 같은 Transaction으로 함께 Commit되거나 함께 Rollback되어야 한다.
 *
 * <p>실제 Spring이 관리하는 {@link SourceConnectionService} Bean(Proxy를 통한
 * 진짜 {@code @Transactional} 동작)과 실제 Testcontainers PostgreSQL을
 * 사용한다 - Service를 Mock하지 않고, 예외가 던져졌다는 사실만 확인하는 데
 * 그치지 않는다: 실패한 호출이 돌아온 뒤 별도 Repository 호출로 실제 Commit된
 * DB 상태를 직접 조회해 확인한다.</p>
 *
 * <p>이 Context에는 {@code SourceTokenStore} Bean을 의도적으로 등록하지 않는다
 * (아직 실제 구현체가 없는 현재 상태 그대로) - Token 관련 검증은
 * {@link SourceConnectionServiceTokenRemovalTest}가 별도 Context에서
 * 담당한다.</p>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, SourceConnectionServiceAuditTransactionTest.ControllableAuditPortConfig.class})
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class SourceConnectionServiceAuditTransactionTest {

    @Autowired
    private SourceConnectionService sourceConnectionService;

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;

    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;

    @Autowired
    private AuditLogJpaRepository auditLogJpaRepository;

    @Autowired
    private AuditEventPort auditEventPort;

    @AfterEach
    void resetAuditFailureToggle() {
        toggleable().setShouldFail(false);
    }

    private ToggleableAuditEventPort toggleable() {
        return (ToggleableAuditEventPort) auditEventPort;
    }

    @Test
    void creationAuditFailureLeavesNoCreatedSource() {
        toggleable().setShouldFail(true);
        String ownerSubject = "owner-audit-fail-create";

        assertThatThrownBy(() -> sourceConnectionService.create(newConnection(ownerSubject)))
                .isInstanceOf(RuntimeException.class);

        assertThat(sourceConnectionJpaRepository.findAllByOwnerSubject(ownerSubject)).isEmpty();
    }

    @Test
    void successfulCreationStoresMatchingAuditEvent() {
        String ownerSubject = "owner-audit-success-create";

        SourceConnection created = sourceConnectionService.create(newConnection(ownerSubject));

        List<AuditLogEntity> matches = auditLogJpaRepository.findAll().stream()
                .filter(e -> ("source:" + created.getId()).equals(e.getTargetId()))
                .filter(e -> "SOURCE_CREATED".equals(e.getAction()))
                .toList();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getActor()).isEqualTo(ownerSubject);
        assertThat(matches.get(0).getResult()).isEqualTo("SUCCESS");
    }

    @Test
    void disconnectAuditFailureLeavesConnectionStatusAndDocumentsUnchanged() {
        String ownerSubject = "owner-audit-fail-disconnect";
        SourceConnection created = sourceConnectionService.create(newConnection(ownerSubject));
        sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(created.getId(), "doc-a", "Doc A", "text/plain", null, null,
                        "ACTIVE", "PENDING", null));

        toggleable().setShouldFail(true);

        assertThatThrownBy(() -> sourceConnectionService.disconnect(created.getId(), ownerSubject))
                .isInstanceOf(RuntimeException.class);

        SourceConnectionEntity unchanged = sourceConnectionJpaRepository.findById(created.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo("ACTIVE");
        assertThat(sourceDocumentJpaRepository.findBySourceIdAndStateNot(created.getId(), "DELETED")).hasSize(1);
    }

    @Test
    void successfulDisconnectStoresMatchingAuditEvent() {
        String ownerSubject = "owner-audit-success-disconnect";
        SourceConnection created = sourceConnectionService.create(newConnection(ownerSubject));

        sourceConnectionService.disconnect(created.getId(), ownerSubject);

        List<AuditLogEntity> matches = auditLogJpaRepository.findAll().stream()
                .filter(e -> ("source:" + created.getId()).equals(e.getTargetId()))
                .filter(e -> "SOURCE_DISCONNECTED".equals(e.getAction()))
                .toList();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getActor()).isEqualTo(ownerSubject);
    }

    @Test
    void nonBlankTokenRefWithNoStoreAvailableFailsClosedLeavingStateUnchanged() {
        String ownerSubject = "owner-no-token-store";
        SourceConnection created = sourceConnectionService.create(newConnection(ownerSubject));
        SourceConnectionEntity entity = sourceConnectionJpaRepository.findById(created.getId()).orElseThrow();
        entity.updateTokenRef("pre-existing-token-ref");
        sourceConnectionJpaRepository.saveAndFlush(entity);

        assertThatThrownBy(() -> sourceConnectionService.disconnect(created.getId(), ownerSubject))
                .isInstanceOf(IllegalStateException.class);

        SourceConnectionEntity unchanged = sourceConnectionJpaRepository.findById(created.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo("ACTIVE");
        assertThat(unchanged.getTokenRef()).isEqualTo("pre-existing-token-ref");
    }

    @Test
    void anotherOwnerCannotDisconnectAndConnectionRemainsUnchanged() {
        String ownerSubject = "owner-cross-account-audit";
        SourceConnection created = sourceConnectionService.create(newConnection(ownerSubject));

        assertThatThrownBy(() -> sourceConnectionService.disconnect(created.getId(), "someone-else"))
                .isInstanceOf(NotFoundException.class);

        SourceConnectionEntity unchanged = sourceConnectionJpaRepository.findById(created.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo("ACTIVE");
    }

    private static SourceConnection newConnection(String ownerSubject) {
        return new SourceConnection(null, SourceType.GOOGLE_DRIVE, "Test Source", ownerSubject,
                SourceConnection.STATUS_ACTIVE, "FULL", null, null);
    }

    /** 실제 {@code save()}를 실제 Adapter로 위임하되, 테스트에서 실패를 켰다 껐다 할 수 있는 Test Double. */
    static class ToggleableAuditEventPort implements AuditEventPort {
        private final AuditEventPort delegate;
        private final AtomicBoolean shouldFail = new AtomicBoolean(false);

        ToggleableAuditEventPort(AuditEventPort delegate) {
            this.delegate = delegate;
        }

        void setShouldFail(boolean value) {
            shouldFail.set(value);
        }

        @Override
        public void save(AuditEvent event) {
            if (shouldFail.get()) {
                throw new IllegalStateException("simulated audit persistence outage");
            }
            delegate.save(event);
        }
    }

    @TestConfiguration
    static class ControllableAuditPortConfig {

        @Bean
        @Primary
        AuditEventPort controllableAuditEventPort(AuditLogPersistenceAdapter realAdapter) {
            return new ToggleableAuditEventPort(realAdapter);
        }
    }
}
