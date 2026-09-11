package com.sdv.policy.application;

import com.sdv.audit.application.port.AuditEventPort;
import com.sdv.audit.domain.AuditEvent;
import com.sdv.audit.infrastructure.persistence.AuditLogPersistenceAdapter;
import com.sdv.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.sdv.audit.infrastructure.persistence.repository.AuditLogJpaRepository;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.policy.domain.PolicyDecision;
import com.sdv.policy.domain.PolicyReasonCode;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M05 감사(Audit) 검증 - 실제 Spring이 관리하는 {@link EffectivePermissionService} Bean과
 * 실제 Testcontainers PostgreSQL을 사용한다. Service를 Mock하지 않고, 실패한 호출이
 * 돌아온 뒤 별도 Repository 호출로 실제 커밋된 DB 상태를 조회해 확인한다(M04에서
 * 확립한 것과 동일한 패턴).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, EffectivePermissionServiceAuditTest.ControllableAuditPortConfig.class})
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.policy.permission-freshness-max-age=PT24H"
})
class EffectivePermissionServiceAuditTest {

    private static final String ACTION = "VIEW";

    @Autowired
    private EffectivePermissionService effectivePermissionService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private SourcePermissionJpaRepository sourcePermissionJpaRepository;
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
    void allowedDecisionStoresSanitizedAuditMetadata() {
        String owner = "owner-audit-allow-" + UUID.randomUUID();
        long documentId = createDocument(owner);
        grantFreshRead(documentId, owner);

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isAllowed()).isTrue();
        AuditLogEntity row = onlyAuditRowFor(documentId);
        assertThat(row.getActor()).isEqualTo(owner);
        assertThat(row.getAction()).isEqualTo("POLICY_DECISION");
        assertThat(row.getResult()).isEqualTo("ALLOW");
        assertThat(row.getReasonCode()).isEqualTo(PolicyReasonCode.ALLOWED.name());
        assertThat(row.getMetadata()).containsEntry("action", ACTION);
    }

    @Test
    void deniedDecisionStoresSanitizedAuditMetadata() {
        String owner = "owner-audit-deny-" + UUID.randomUUID();
        long documentId = createDocument(owner);
        // Deliberately no permission row -> SOURCE_PERMISSION_DENIED... actually
        // zero rows means PERMISSION_DATA_UNTRUSTED; grant a non-matching row instead.
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(documentId, "user", "someone-else", "READ", Instant.now()));

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        AuditLogEntity row = onlyAuditRowFor(documentId);
        assertThat(row.getResult()).isEqualTo("DENY");
        assertThat(row.getReasonCode()).isEqualTo(PolicyReasonCode.SOURCE_PERMISSION_DENIED.name());
    }

    @Test
    void auditFailureDoesNotLeaveAFalselySuccessfulPolicyOperation() {
        String owner = "owner-audit-fail-" + UUID.randomUUID();
        long documentId = createDocument(owner);
        grantFreshRead(documentId, owner);
        toggleable().setShouldFail(true);

        assertThatThrownBy(() -> effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null))
                .isInstanceOf(RuntimeException.class);

        // No audit row was durably committed for this decision, and the caller
        // never received a PolicyDecision it could have mistakenly trusted.
        assertThat(auditLogJpaRepository.findAll().stream()
                .filter(e -> ("document:" + documentId).equals(e.getTargetId()))
                .toList()).isEmpty();
    }

    private AuditLogEntity onlyAuditRowFor(long documentId) {
        List<AuditLogEntity> matches = auditLogJpaRepository.findAll().stream()
                .filter(e -> ("document:" + documentId).equals(e.getTargetId()))
                .filter(e -> "POLICY_DECISION".equals(e.getAction()))
                .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private long createDocument(String ownerSubject) {
        SourceConnectionEntity connection =
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(),
                "doc-" + UUID.randomUUID(), "Doc", "text/plain", null, null, "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return document.getId();
    }

    private void grantFreshRead(long documentId, String ownerSubject) {
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(documentId, "user", ownerSubject, "READ", Instant.now()));
    }

    private static UserContext userContext(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of());
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
