package com.sdv.rag.infrastructure.persistence.repository;

import com.sdv.rag.infrastructure.persistence.entity.DocumentExtractedContentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentExtractedContentJpaRepository}의 원자적 조건부 Claim/Publish
 * SQL을 직접 검증한다 - {@link com.sdv.rag.application.ContentExtractionService}
 * 전체 조율 없이, "늦게 끝난 시도가 재점유된 Claim을 뒤늦게 Publish하지
 * 못한다"는 핵심 메커니즘을 결정적으로(Deterministic) 재현한다(실제 Thread
 * 동시 실행 없이 - V1/V2 역전 완료 시나리오의 근본 메커니즘).
 *
 * <p>{@code @Transactional}은 이 Repository의 {@code @Modifying} Native Query가
 * 요구하는 활성 Transaction을 테스트 메서드 전체에 제공하기 위함이다(Service
 * Layer 없이 Repository를 직접 호출하므로) - 각 테스트 종료 후 자동
 * Rollback되어 테스트 간 격리도 함께 얻는다.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
@Transactional
class DocumentExtractedContentJpaRepositoryTest {

    @Autowired
    private DocumentExtractedContentJpaRepository repository;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;

    @Test
    void tryClaimSucceedsWhenNoRowExists() {
        long documentId = createDocument();

        int rows = repository.tryClaim(documentId, UUID.randomUUID(), Instant.now(),
                Instant.now().minusSeconds(90));

        assertThat(rows).isEqualTo(1);
        assertThat(repository.findById(documentId)).isPresent();
        assertThat(repository.findById(documentId).get().isPublished()).isFalse();
    }

    @Test
    void tryClaimFailsWhenAlreadyClaimedAndNotStale() {
        long documentId = createDocument();
        UUID firstAttempt = UUID.randomUUID();
        Instant now = Instant.now();
        repository.tryClaim(documentId, firstAttempt, now, now.minusSeconds(90));

        int rows = repository.tryClaim(documentId, UUID.randomUUID(), now, now.minusSeconds(90));

        assertThat(rows).isEqualTo(0);
        assertThat(repository.findById(documentId).get().getAttemptId()).isEqualTo(firstAttempt);
    }

    @Test
    void tryClaimReclaimsWhenExistingClaimIsStale() {
        long documentId = createDocument();
        UUID staleAttempt = UUID.randomUUID();
        Instant longAgo = Instant.now().minusSeconds(500);
        repository.tryClaim(documentId, staleAttempt, longAgo, longAgo.minusSeconds(90));

        UUID newAttempt = UUID.randomUUID();
        Instant now = Instant.now();
        int rows = repository.tryClaim(documentId, newAttempt, now, now.minusSeconds(90));

        assertThat(rows).isEqualTo(1);
        assertThat(repository.findById(documentId).get().getAttemptId()).isEqualTo(newAttempt);
    }

    @Test
    void publishSucceedsOnlyWhileAttemptIdStillMatches() {
        long documentId = createDocument();
        UUID attemptId = UUID.randomUUID();
        repository.tryClaim(documentId, attemptId, Instant.now(), Instant.now().minusSeconds(90));

        int rows = repository.publishIfAttemptStillOwned(documentId, attemptId, "v1", "hash1", "plaintext", "1",
                "1", "hello", "[]", Instant.now());

        assertThat(rows).isEqualTo(1);
        DocumentExtractedContentEntity published = repository.findById(documentId).orElseThrow();
        assertThat(published.isPublished()).isTrue();
        assertThat(published.getAttemptId()).isNull();
        assertThat(published.getNormalizedText()).isEqualTo("hello");
    }

    /**
     * 핵심 회귀: attempt_id가 더 이상 일치하지 않는(Stale로 재점유된) 시도가
     * 뒤늦게 Publish를 시도하면 0행에 적용되고 실패한다 - 더 새로운 시도의
     * 결과(또는 진행 중인 Claim)를 절대 덮어쓰지 않는다.
     */
    @Test
    void lateAttemptCannotPublishAfterItsClaimWasReclaimed() {
        long documentId = createDocument();
        UUID v1Attempt = UUID.randomUUID();
        Instant longAgo = Instant.now().minusSeconds(500);
        repository.tryClaim(documentId, v1Attempt, longAgo, longAgo.minusSeconds(90));

        // v1의 Claim이 Stale 판정되어 v2가 재점유하고 먼저 발행을 끝낸다.
        UUID v2Attempt = UUID.randomUUID();
        Instant now = Instant.now();
        repository.tryClaim(documentId, v2Attempt, now, now.minusSeconds(90));
        int v2Published = repository.publishIfAttemptStillOwned(documentId, v2Attempt, "v2", "hash2", "plaintext",
                "1", "1", "v2 content", "[]", now);
        assertThat(v2Published).isEqualTo(1);

        // v1이 이제야(뒤늦게) 자신의 attempt_id로 발행을 시도한다 - 더 이상 일치하지
        // 않으므로 0행, v2의 결과는 그대로 남는다.
        int v1LatePublish = repository.publishIfAttemptStillOwned(documentId, v1Attempt, "v1", "hash1", "plaintext",
                "1", "1", "v1 content (late)", "[]", Instant.now());

        assertThat(v1LatePublish).isEqualTo(0);
        DocumentExtractedContentEntity current = repository.findById(documentId).orElseThrow();
        assertThat(current.getNormalizedText()).isEqualTo("v2 content");
        assertThat(current.getSourceVersion()).isEqualTo("v2");
    }

    @Test
    void releaseClaimClearsClaimWithoutTouchingPreviouslyPublishedContent() {
        long documentId = createDocument();
        UUID firstAttempt = UUID.randomUUID();
        repository.tryClaim(documentId, firstAttempt, Instant.now(), Instant.now().minusSeconds(90));
        repository.publishIfAttemptStillOwned(documentId, firstAttempt, "v1", "hash1", "plaintext", "1", "1",
                "original content", "[]", Instant.now());

        UUID retryAttempt = UUID.randomUUID();
        repository.tryClaim(documentId, retryAttempt, Instant.now(), Instant.now().minusSeconds(90));
        int released = repository.releaseClaim(documentId, retryAttempt);

        assertThat(released).isEqualTo(1);
        DocumentExtractedContentEntity current = repository.findById(documentId).orElseThrow();
        assertThat(current.getAttemptId()).isNull();
        assertThat(current.getNormalizedText()).isEqualTo("original content");
    }

    @Test
    void retryingASuccessfulPublishUpdatesTheSameRowRatherThanDuplicating() {
        long documentId = createDocument();
        UUID attempt1 = UUID.randomUUID();
        repository.tryClaim(documentId, attempt1, Instant.now(), Instant.now().minusSeconds(90));
        repository.publishIfAttemptStillOwned(documentId, attempt1, "v1", "hash1", "plaintext", "1", "1", "first",
                "[]", Instant.now());

        UUID attempt2 = UUID.randomUUID();
        repository.tryClaim(documentId, attempt2, Instant.now(), Instant.now().minusSeconds(90));
        repository.publishIfAttemptStillOwned(documentId, attempt2, "v1", "hash1", "plaintext", "1", "1", "first",
                "[]", Instant.now());

        assertThat(repository.count()).isEqualTo(1);
        Optional<DocumentExtractedContentEntity> row = repository.findById(documentId);
        assertThat(row).isPresent();
        assertThat(row.get().getNormalizedText()).isEqualTo("first");
    }

    private long createDocument() {
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE",
                "FULL", "owner-" + UUID.randomUUID());
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), "doc-" + UUID.randomUUID(),
                "Doc", "text/plain", "v1", null, "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return document.getId();
    }
}
