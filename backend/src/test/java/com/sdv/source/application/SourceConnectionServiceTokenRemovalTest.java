package com.sdv.source.application;

import com.sdv.common.exception.NotFoundException;
import com.sdv.source.application.port.SourceTokenStore;
import com.sdv.source.application.port.TokenEnvelope;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M04 후속 교정 검증(항목 2): 실제 Spring이 관리하는
 * {@link SourceConnectionService} Bean과 실제 Testcontainers PostgreSQL 위에서
 * Token 제거 성공/실패/재시도/멱등성/Cross-Account 차단을 검증한다.
 *
 * <p>Production Stub을 추가하지 않는다 - {@link RecordingSourceTokenStore}는
 * 이 테스트 파일 안에만 존재하는 Test Double이다.</p>
 *
 * <p><b>재현하지 않는(재현 불가능한) 부분:</b> "외부 Token은 실제로 폐기됐지만
 * DB Transaction은 이후 단계에서 Rollback된" 교차 상태는 여기서 수치로
 * 재현하지 않는다 - {@link RecordingSourceTokenStore#deletedSourceIds()}가
 * "Rollback 여부와 무관하게 delete() 호출 자체는 이미 발생했다"는 사실을
 * 보여줄 뿐이며(Cross-System 부작용은 되돌릴 수 없음을 관찰 가능하게
 * 한다), 실제 외부 Provider의 최종 상태를 재현하지는 않는다 - 이는
 * {@link SourceConnectionService}의 클래스 Javadoc에 문서화된 근본적
 * 한계다.</p>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, SourceConnectionServiceTokenRemovalTest.RecordingTokenStoreConfig.class})
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class SourceConnectionServiceTokenRemovalTest {

    @Autowired
    private SourceConnectionService sourceConnectionService;

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;

    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;

    @Autowired
    private SourceTokenStore sourceTokenStore;

    @AfterEach
    void resetStore() {
        recording().reset();
    }

    private RecordingSourceTokenStore recording() {
        return (RecordingSourceTokenStore) sourceTokenStore;
    }

    @Test
    void tokenStoreDeleteFailureLeavesDbStateUnchanged() {
        String ownerSubject = "owner-token-delete-fails";
        long sourceId = createWithTokenRefAndDocument(ownerSubject, "token-ref-fail-case");
        recording().setShouldFailOnDelete(true);

        assertThatThrownBy(() -> sourceConnectionService.disconnect(sourceId, ownerSubject))
                .isInstanceOf(RuntimeException.class);

        SourceConnectionEntity unchanged = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo("ACTIVE");
        assertThat(unchanged.getTokenRef()).isEqualTo("token-ref-fail-case");
        assertThat(sourceDocumentJpaRepository.findBySourceIdAndStateNot(sourceId, "DELETED")).hasSize(1);
    }

    @Test
    void successfulTokenDeletionClearsReferenceAndCascadesStatusAndDocuments() {
        String ownerSubject = "owner-token-delete-success";
        long sourceId = createWithTokenRefAndDocument(ownerSubject, "token-ref-success-case");

        sourceConnectionService.disconnect(sourceId, ownerSubject);

        SourceConnectionEntity afterDisconnect = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(afterDisconnect.getStatus()).isEqualTo("DISABLED");
        assertThat(afterDisconnect.getTokenRef()).isNull();
        assertThat(sourceDocumentJpaRepository.findBySourceIdAndStateNot(sourceId, "DELETED")).isEmpty();
        assertThat(recording().deletedSourceIds()).containsExactly(sourceId);
    }

    /**
     * M08 후속 교정: {@code disconnect()}는 이제 이 Read 시점의 Stale한 {@code
     * tokenRef} 값만으로 {@code delete()} 호출을 건너뛰지 않는다("ensure disconnect
     * coordinates even when no token was present at its initial read" - 이 작업
     * 지시사항 참고, {@code SourceConnectionService.disconnect} Class Javadoc의
     * "M08 후속 교정" 참고) - 반복 호출에서도 항상 {@code delete()}를 호출하고,
     * 멱등성은 그 구현체 자신({@code GoogleTokenService.revoke})의 책임이다. 이
     * Test Double은 실제 멱등적 구현체가 아니라 단순 "호출됐다" Recorder이므로, 두
     * 번째 호출도 기록에 남는 것이 이제 올바른 관찰 결과다.
     */
    @Test
    void repeatedSuccessfulDisconnectCallsDeleteAgainIdempotentlyAndStaysDisabled() {
        String ownerSubject = "owner-token-repeat-disconnect";
        long sourceId = createWithTokenRefAndDocument(ownerSubject, "token-ref-repeat-case");

        sourceConnectionService.disconnect(sourceId, ownerSubject);
        assertThat(recording().deletedSourceIds()).containsExactly(sourceId);

        // Second call: tokenRef is already null in the DB, but disconnect() no longer trusts
        // that stale-read fact alone - it always asks the store again, relying on the store's
        // own idempotency (real GoogleTokenService.revoke is a safe no-op with nothing stored).
        sourceConnectionService.disconnect(sourceId, ownerSubject);

        assertThat(recording().deletedSourceIds())
                .as("delete() is called again on repeat disconnect - idempotency is the store's own responsibility now")
                .containsExactly(sourceId, sourceId);
        SourceConnectionEntity stillDisabled = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(stillDisabled.getStatus()).isEqualTo("DISABLED");
        assertThat(stillDisabled.getTokenRef()).isNull();
    }

    @Test
    void unrelatedOwnersSourceCannotTriggerTokenDeletion() {
        String realOwner = "owner-token-real";
        long sourceId = createWithTokenRefAndDocument(realOwner, "token-ref-cross-account");

        assertThatThrownBy(() -> sourceConnectionService.disconnect(sourceId, "owner-token-attacker"))
                .isInstanceOf(NotFoundException.class);

        assertThat(recording().deletedSourceIds()).as("delete() must never be called for another owner's source")
                .isEmpty();
        SourceConnectionEntity unchanged = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo("ACTIVE");
        assertThat(unchanged.getTokenRef()).isEqualTo("token-ref-cross-account");
        assertThat(sourceDocumentJpaRepository.findBySourceIdAndStateNot(sourceId, "DELETED")).hasSize(1);
    }

    private long createWithTokenRefAndDocument(String ownerSubject, String tokenRef) {
        SourceConnection created = sourceConnectionService.create(
                new SourceConnection(null, SourceType.GOOGLE_DRIVE, "Test Source", ownerSubject,
                        SourceConnection.STATUS_ACTIVE, "FULL", null, null));
        SourceConnectionEntity entity = sourceConnectionJpaRepository.findById(created.getId()).orElseThrow();
        entity.updateTokenRef(tokenRef);
        sourceConnectionJpaRepository.saveAndFlush(entity);
        sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(created.getId(), "doc-a", "Doc A", "text/plain", null, null,
                        "ACTIVE", "PENDING", null));
        return created.getId();
    }

    /** Test Double only - no production stub. Records every {@code delete(sourceId)} call. */
    static class RecordingSourceTokenStore implements SourceTokenStore {
        private final List<Long> deletedSourceIds = new CopyOnWriteArrayList<>();
        private final AtomicBoolean shouldFailOnDelete = new AtomicBoolean(false);

        @Override
        public void save(Long sourceId, TokenEnvelope token) {
            // not exercised by disconnect(); no-op for this test double.
        }

        @Override
        public java.util.Optional<TokenEnvelope> load(Long sourceId) {
            return java.util.Optional.empty();
        }

        @Override
        public void delete(Long sourceId) {
            if (shouldFailOnDelete.get()) {
                throw new IllegalStateException("simulated token store outage");
            }
            deletedSourceIds.add(sourceId);
        }

        List<Long> deletedSourceIds() {
            return List.copyOf(deletedSourceIds);
        }

        void setShouldFailOnDelete(boolean value) {
            shouldFailOnDelete.set(value);
        }

        void reset() {
            deletedSourceIds.clear();
            shouldFailOnDelete.set(false);
        }
    }

    @TestConfiguration
    static class RecordingTokenStoreConfig {

        // M08 MVP OAuth 후속 교정: GoogleTokenStoreAdapter가 이제 실제 SourceTokenStore
        // Bean으로 항상 등록된다(이 Class Javadoc의 "Production Stub을 추가하지 않는다"는
        // 이 Test Double 자체에 대한 서술이지, 이 파일이 그 실제 Bean과의 충돌을 피해도
        // 된다는 뜻은 아니다) - @Primary로 이 Test Double을 명시적으로 우선시켜, 단일
        // SourceTokenStore Bean을 기대하는 @Autowired 지점이 계속 결정론적으로 이
        // RecordingSourceTokenStore를 받게 한다.
        @Bean
        @Primary
        RecordingSourceTokenStore recordingSourceTokenStore() {
            return new RecordingSourceTokenStore();
        }
    }
}
