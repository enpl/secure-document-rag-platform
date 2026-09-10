package com.sdv.source.application;

import com.sdv.common.exception.NotFoundException;
import com.sdv.source.application.port.SourceTokenStore;
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

    @Test
    void repeatedSuccessfulDisconnectDoesNotDeleteAlreadyClearedTokenAgain() {
        String ownerSubject = "owner-token-repeat-disconnect";
        long sourceId = createWithTokenRefAndDocument(ownerSubject, "token-ref-repeat-case");

        sourceConnectionService.disconnect(sourceId, ownerSubject);
        assertThat(recording().deletedSourceIds()).containsExactly(sourceId);

        // Second call: tokenRef is already null, so the store must not be invoked again.
        sourceConnectionService.disconnect(sourceId, ownerSubject);

        assertThat(recording().deletedSourceIds()).as("delete() must not be called a second time").containsExactly(sourceId);
        SourceConnectionEntity stillDisabled = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        assertThat(stillDisabled.getStatus()).isEqualTo("DISABLED");
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
        public void save(Long sourceId, String token) {
            // not exercised by disconnect(); no-op for this test double.
        }

        @Override
        public String load(Long sourceId) {
            return null;
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

        @Bean
        RecordingSourceTokenStore recordingSourceTokenStore() {
            return new RecordingSourceTokenStore();
        }
    }
}
