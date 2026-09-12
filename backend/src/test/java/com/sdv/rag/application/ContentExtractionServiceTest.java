package com.sdv.rag.application;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.rag.application.ContentExtractionService.ExtractionOutcome;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M07A 교정 이후의 {@link ContentExtractionService} 회귀 - V006이
 * {@code document_extracted_content}(V005)를 제거했으므로, 이 Class는 더 이상
 * Claim/Fetch/Parse/Publish를 수행하지 않는다(같은 패키지의 클래스 Javadoc
 * 참고). 이 테스트는 남은 유일한 두 결과(인가 거부/명시적 미구현)만 검증한다
 * - Claim 경쟁, Lock 순서, Retry 등 V005 시절의 동시성 테스트는 그 대상
 * 메커니즘 자체가 사라졌으므로 더 이상 유효하지 않다(삭제됨, 이전 버전
 * 이력에서 확인 가능).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.policy.permission-freshness-max-age=PT24H"
})
class ContentExtractionServiceTest {

    @Autowired
    private ContentExtractionService contentExtractionService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private SourcePermissionJpaRepository sourcePermissionJpaRepository;
    @Autowired
    private DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository;

    @Test
    void deniedCallerNeverReachesTheExtractionDecision() {
        String owner = "owner-denied-" + unique();
        String attacker = "attacker-" + unique();
        long documentId = createDocument(owner);
        grantFreshRead(documentId, owner);

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(attacker), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.DENIED);
    }

    @Test
    void staleAclEvidenceIsDeniedBeforeAnyProcessing() {
        String owner = "owner-stale-acl-" + unique();
        long documentId = createDocument(owner);
        sourcePermissionJpaRepository.saveAndFlush(new SourcePermissionEntity(documentId, "user", owner, "READ",
                Instant.now().minus(Duration.ofHours(48))));

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.DENIED);
    }

    /**
     * 핵심 회귀(M07A) - 인가를 통과한 요청도 실제 처리를 시도하지 않고
     * 명시적으로 REJECTED를 반환한다. V006이 제거한 평문 저장소로 되돌아가지
     * 않고, 근거 없이 SUCCESS/INDEXED를 만들어내지도 않는다 - "아직 구현되지
     * 않음"을 정직하게 보고한다.
     */
    @Test
    void authorizedRequestReceivesAnExplicitNotYetImplementedRejectionRatherThanFabricatedSuccess() {
        String owner = "owner-authorized-" + unique();
        long documentId = createDocument(owner);
        grantFreshRead(documentId, owner);

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.REJECTED);
        assertThat(outcome.detail()).containsIgnoringCase("not yet implemented");
        // 되살아난/새로 생긴 평문도, Embedding Index 행도 전혀 없다.
        assertThat(documentEmbeddingJpaRepository.findAll()).isEmpty();
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .as("an unimplemented path must never fabricate INDEXED")
                .isNotEqualTo("INDEXED");
    }

    private long createDocument(String ownerSubject) {
        SourceConnectionEntity connection =
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), "doc-" + unique(), "Doc",
                "text/plain", "v1", null, "ACTIVE", "PENDING", null);
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

    private static String unique() {
        return UUID.randomUUID().toString();
    }
}
