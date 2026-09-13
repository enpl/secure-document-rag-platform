package com.sdv.source.application;

import com.sdv.audit.application.port.AuditEventPort;
import com.sdv.audit.domain.AuditEvent;
import com.sdv.audit.infrastructure.persistence.AuditLogPersistenceAdapter;
import com.sdv.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.sdv.audit.infrastructure.persistence.repository.AuditLogJpaRepository;
import com.sdv.common.exception.NotFoundException;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.google.GoogleOAuthClient;
import com.sdv.source.infrastructure.google.GoogleTokenService;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceOAuthTokenJpaRepository;
import com.sdv.source.application.port.TokenEnvelope;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

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
 * <p><b>M08 MVP OAuth 후속 교정:</b> 이 Context는 더 이상 {@code SourceTokenStore}
 * Bean이 없는 상태를 재현하지 못한다 - {@code GoogleTokenStoreAdapter}가 이제 항상
 * Component Scan으로 등록되는 실제 Production Bean이기 때문이다(M08 이전에는 이
 * Class Javadoc이 "의도적으로 등록하지 않는다"고 정확히 서술했었다). Token 결합/
 * 암호화/Refresh/Revoke 관련 상세 검증은 {@link SourceConnectionServiceTokenRemovalTest}와
 * {@code GoogleTokenServiceTest}가 담당하고, "Token Store Bean 자체가 없는" 방어
 * 분기 자체는 Spring Context 없이 {@link SourceConnectionServiceNoTokenStoreTest}가
 * 계속 검증한다.</p>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, SourceConnectionServiceAuditTransactionTest.ControllableAuditPortConfig.class})
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        // 합성(Synthetic) Test 전용 32-byte AES Key - 실제 Secret이 아니다.
        "sdv.secrets.token-encryption-key=354C9a3yRzwUV/1rt1s8AX4yflicnzQj0Z8aItHYw3w=",
        "sdv.secrets.token-encryption-key-id=test-key-v1"
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

    @Autowired
    private GoogleTokenService googleTokenService;

    @Autowired
    private SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private GoogleOAuthClient googleOAuthClient;

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

    /**
     * M08 이전에는 이 시나리오("Token Store Bean 자체가 없음")가 {@code
     * IllegalStateException}으로 Fail Closed 했다(이 Class Javadoc 참고 - 그 방어
     * 분기 자체는 {@link SourceConnectionServiceNoTokenStoreTest}가 Spring Context
     * 없이 계속 검증한다). 이 Context에는 이제 실제 {@code GoogleTokenStoreAdapter}
     * Bean이 항상 존재하므로, {@code source_oauth_tokens}에 대응 행이 없는 낡은/
     * 손상된 {@code token_ref} 문자열은 대신 {@code GoogleTokenService.revoke}의
     * 멱등성 규칙(대응 행이 없으면 조용히 아무 일도 하지 않는다 - {@code
     * SourceTokenStore.delete}의 기존 계약)에 따라 처리되어, Disconnect 자체는
     * 안전하게 성공해야 한다(영구히 막히지 않아야 한다).
     */
    @Test
    void nonBlankTokenRefWithNoMatchingEncryptedRowStillDisconnectsSafely() {
        String ownerSubject = "owner-orphaned-token-ref";
        SourceConnection created = sourceConnectionService.create(newConnection(ownerSubject));
        SourceConnectionEntity entity = sourceConnectionJpaRepository.findById(created.getId()).orElseThrow();
        entity.updateTokenRef("pre-existing-token-ref-with-no-matching-row");
        sourceConnectionJpaRepository.saveAndFlush(entity);

        sourceConnectionService.disconnect(created.getId(), ownerSubject);

        SourceConnectionEntity afterDisconnect = sourceConnectionJpaRepository.findById(created.getId()).orElseThrow();
        assertThat(afterDisconnect.getStatus()).isEqualTo("DISABLED");
        assertThat(afterDisconnect.getTokenRef()).isNull();
    }

    /**
     * M08 3-issue 교정(이 작업 지시사항 2번) - "Add a real-service disconnect regression for
     * unreadable credentials: no Google call, no success audit, no local destructive changes."
     * 실제 {@link SourceConnectionService#disconnect}(Fake가 아니다)를 그대로 호출한다.
     */
    @Test
    void disconnectWithAnUnreadableCredentialFailsClosedWithoutGoogleCallSuccessAuditOrDestructiveChanges() {
        String ownerSubject = "owner-unreadable-credential-disconnect";
        SourceConnection created = sourceConnectionService.create(newConnection(ownerSubject));
        long sourceId = created.getId();
        sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(sourceId, "doc-a", "Doc A", "text/plain", null, null, "ACTIVE", "PENDING",
                        null));
        googleTokenService.store(sourceId, new TokenEnvelope(ownerSubject, "access-token", "refresh-token",
                Instant.now().plusSeconds(3600), List.of(GoogleDriveConnector.REQUIRED_SCOPE_DRIVE_READONLY)));
        // googleTokenService.store()는 자신만의 독립된 Transaction에서 이미 Commit됐다 - 별도
        // Flush 없이도 Raw JDBC로 바로 보인다(이 Test Class는 SourceConnectionServiceTokenRemovalTest와
        // 같은 관례를 따른다 - Method 전체를 감싸는 @Transactional을 쓰지 않는다).
        // Ciphertext를 변조해 복호화가 실패하게 만든다(변조/Key 불일치/Reference 불일치 중 한 대표 경우).
        byte[] ciphertext = jdbcTemplate.queryForObject(
                "SELECT ciphertext FROM source_oauth_tokens WHERE source_id = ?", byte[].class, sourceId);
        byte[] tampered = ciphertext.clone();
        tampered[tampered.length - 1] ^= 0x01;
        jdbcTemplate.update("UPDATE source_oauth_tokens SET ciphertext = ? WHERE source_id = ?", tampered, sourceId);

        assertThatThrownBy(() -> sourceConnectionService.disconnect(sourceId, ownerSubject))
                .as("an unreadable credential must fail closed, not be silently treated as a successful revocation")
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(googleOAuthClient);
        SourceConnectionEntity unchanged = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(unchanged.getStatus()).as("no destructive status change").isEqualTo("ACTIVE");
        assertThat(unchanged.getTokenRef()).as("token_ref must be preserved for retry after key repair").isNotBlank();
        assertThat(sourceOAuthTokenJpaRepository.findBySourceId(sourceId))
                .as("the local encrypted row must be preserved, not silently deleted")
                .isPresent();
        assertThat(sourceDocumentJpaRepository.findBySourceIdAndStateNot(sourceId, "DELETED"))
                .as("the existing document must not be marked deleted by a failed disconnect")
                .hasSize(1);
        List<AuditLogEntity> successAudits = auditLogJpaRepository.findAll().stream()
                .filter(e -> ("source:" + sourceId).equals(e.getTargetId()))
                .filter(e -> "SOURCE_DISCONNECTED".equals(e.getAction()))
                .filter(e -> "SUCCESS".equals(e.getResult()))
                .toList();
        assertThat(successAudits).as("no SUCCESS disconnect audit for a disconnect that did not actually happen")
                .isEmpty();
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
