package com.sdv.rag.application;

import com.sdv.event.domain.IndexRequestedEvent;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.rag.infrastructure.persistence.repository.ProcessedEventJpaRepository;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M11 후속 교정 검증(이 작업 지시사항 1번) - {@link IndexBackfillService}가 더 이상
 * "과거에 한 번이라도 INDEXED 이력이 있었는가"가 아니라 "지금 현재 source_version에
 * 대응하는 Embedding Generation이 실제로 존재하는가"로 후보를 판단함을, 그리고 겹치거나
 * 반복되는 호출이 같은 문서에 중복 작업을 쌓지 않음을 실제 Testcontainers PostgreSQL로
 * 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
@Transactional
class IndexBackfillServiceTest {

    @Autowired
    private IndexBackfillService indexBackfillService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private DocumentShareJpaRepository documentShareJpaRepository;
    @Autowired
    private DocumentEmbeddingJpaRepository documentEmbeddingJpaRepository;
    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired
    private ProcessedEventJpaRepository processedEventJpaRepository;

    @Test
    void aDocumentNeverIndexedIsScheduled() {
        Long sourceId = persistSource("owner-backfill-never-indexed");
        Long documentId = persistDocumentAndShare(sourceId, "never-indexed", "v1", "owner-backfill-never-indexed");

        IndexBackfillService.BackfillResult result = indexBackfillService.runOnce(0, 50);

        assertThat(result.publishedCount()).isEqualTo(1);
        assertThat(outboxHasPendingIndexRequestFor(sourceId, "never-indexed")).isTrue();
    }

    /**
     * 핵심 교정 - 이 문서는 과거에 처음(구) Version에서 성공적으로 색인됐었지만, Content가
     * 새 Version으로 바뀐 뒤로는 재색인되지 않았다("a historical INDEXED entry is not
     * proof that its current generation exists or is valid"). 옛 구현은 이력만 보고
     * 영구히 건너뛰었을 것이다 - 이제는 현재 Version 기준으로 다시 후보에 포함된다.
     */
    @Test
    void aDocumentIndexedOnlyAtAnOlderSourceVersionIsScheduledAgain() {
        Long sourceId = persistSource("owner-backfill-stale-version");
        Long documentId = persistDocumentAndShare(sourceId, "stale-version-doc", "v2-current",
                "owner-backfill-stale-version");
        // 옛 Generation - source_version="v1"(현재 문서의 v2-current와 다르다).
        documentEmbeddingJpaRepository.saveAndFlush(new DocumentEmbeddingEntity(documentId, 0, "PAGE", "1",
                "[" + "0,".repeat(1023) + "0]", "v1", "a".repeat(64), "1", "1", "bge-m3:567m", Instant.now()));

        IndexBackfillService.BackfillResult result = indexBackfillService.runOnce(0, 50);

        assertThat(result.publishedCount()).isEqualTo(1);
        assertThat(outboxHasPendingIndexRequestFor(sourceId, "stale-version-doc")).isTrue();
    }

    @Test
    void aDocumentAlreadyIndexedAtItsCurrentVersionIsNotScheduled() {
        Long sourceId = persistSource("owner-backfill-current");
        Long documentId = persistDocumentAndShare(sourceId, "already-current", "v1", "owner-backfill-current");
        documentEmbeddingJpaRepository.saveAndFlush(new DocumentEmbeddingEntity(documentId, 0, "PAGE", "1",
                "[" + "0,".repeat(1023) + "0]", "v1", "a".repeat(64), "1", "1", "bge-m3:567m", Instant.now()));

        IndexBackfillService.BackfillResult result = indexBackfillService.runOnce(0, 50);

        assertThat(result.publishedCount()).isZero();
        assertThat(outboxHasPendingIndexRequestFor(sourceId, "already-current")).isFalse();
    }

    @Test
    void aRevokedOrAdminBlockedShareIsNeverScheduled() {
        Long revokedSourceId = persistSource("owner-backfill-revoked");
        SourceDocumentEntity revokedDoc = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(revokedSourceId, "revoked-doc", "Doc.pdf", "application/pdf", "v1", null,
                        "ACTIVE", "PENDING", null));
        DocumentShareEntity revokedShare = new DocumentShareEntity("owner-backfill-revoked", revokedSourceId,
                revokedDoc.getId(), "INTERNAL", "VIEW", Instant.now());
        revokedShare.revoke(Instant.now());
        documentShareJpaRepository.saveAndFlush(revokedShare);

        Long blockedSourceId = persistSource("owner-backfill-blocked");
        SourceDocumentEntity blockedDoc = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(blockedSourceId, "blocked-doc", "Doc.pdf", "application/pdf", "v1", null,
                        "ACTIVE", "PENDING", null));
        DocumentShareEntity blockedShare = new DocumentShareEntity("owner-backfill-blocked", blockedSourceId,
                blockedDoc.getId(), "INTERNAL", "VIEW", Instant.now());
        blockedShare.applyAdminBlock(true, "policy violation", Instant.now());
        documentShareJpaRepository.saveAndFlush(blockedShare);

        IndexBackfillService.BackfillResult result = indexBackfillService.runOnce(0, 50);

        assertThat(outboxHasPendingIndexRequestFor(revokedSourceId, "revoked-doc")).isFalse();
        assertThat(outboxHasPendingIndexRequestFor(blockedSourceId, "blocked-doc")).isFalse();
        assertThat(result.publishedCount()).isZero();
    }

    /**
     * "prevent overlapping/repeated backfill calls from enqueueing duplicate work for the
     * same current generation" - 아직 소비(Consumption)되지 않은 첫 호출의 PENDING Outbox
     * 요청이 남아있는 동안 다시 호출해도, 같은 문서에 대한 두 번째 요청을 추가로 쌓지 않는다.
     */
    @Test
    void repeatedBackfillCallsBeforeConsumptionDoNotDuplicateEquivalentPendingWork() {
        Long sourceId = persistSource("owner-backfill-repeat");
        persistDocumentAndShare(sourceId, "repeat-doc", "v1", "owner-backfill-repeat");

        IndexBackfillService.BackfillResult first = indexBackfillService.runOnce(0, 50);
        IndexBackfillService.BackfillResult second = indexBackfillService.runOnce(0, 50);

        assertThat(first.publishedCount()).isEqualTo(1);
        assertThat(second.publishedCount())
                .as("a second call before the first request is consumed must not enqueue a duplicate")
                .isZero();
        long pendingCount = outboxEventJpaRepository.findAll().stream()
                .filter(e -> "INDEX_REQUESTED".equals(e.getEventType()))
                .filter(e -> ("source:" + sourceId + ":doc:repeat-doc").equals(e.getPartitionKey()))
                .count();
        assertThat(pendingCount).isEqualTo(1);
        assertThat(second.lastShareId())
                .as("D 후속 교정(Runbook 정정) - publishedCount()==0이어도 이 Page에 후보가 있었다면 "
                        + "cursor(lastShareId)는 그대로 전진한다 - 운영자는 이 값을 보고 종료를 판단해야 한다 "
                        + "(publishedCount()==0만으로 스캔이 끝났다고 오판하면 안 된다)")
                .isEqualTo(first.lastShareId());
    }

    /** D 후속 교정(Runbook 정정) - 진짜 Scan 종료(더 이상 후보 자체가 없음)는 cursor가 전진하지 않는 것으로 구분된다. */
    @Test
    void trueScanExhaustionLeavesTheCursorUnchangedUnlikeAPageThatWasEntirelyAlreadyPending() {
        Long sourceId = persistSource("owner-backfill-exhaustion");
        persistDocumentAndShare(sourceId, "exhaustion-doc", "v1", "owner-backfill-exhaustion");
        IndexBackfillService.BackfillResult firstPass = indexBackfillService.runOnce(0, 50);
        long cursorAfterOnlyDocument = firstPass.lastShareId();

        // 더 이상 후보 자체가 없다(모든 문서가 이미 처리됐거나 없음) - 진짜 Scan 종료.
        IndexBackfillService.BackfillResult exhausted = indexBackfillService.runOnce(cursorAfterOnlyDocument, 50);

        assertThat(exhausted.publishedCount()).isZero();
        assertThat(exhausted.lastShareId())
                .as("true exhaustion (no more candidates at all) leaves the cursor exactly where it was given")
                .isEqualTo(cursorAfterOnlyDocument);
    }

    private boolean outboxHasPendingIndexRequestFor(Long sourceId, String externalDocumentId) {
        String partitionKey = "source:" + sourceId + ":doc:" + externalDocumentId;
        return outboxEventJpaRepository.findAll().stream()
                .anyMatch(e -> IndexRequestedEvent.EVENT_TYPE.equals(e.getEventType())
                        && partitionKey.equals(e.getPartitionKey()));
    }

    private Long persistSource(String ownerSubject) {
        SourceConnectionEntity source = sourceConnectionJpaRepository.saveAndFlush(
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL", ownerSubject));
        return source.getId();
    }

    private Long persistDocumentAndShare(Long sourceId, String externalId, String sourceVersion,
            String ownerSubject) {
        SourceDocumentEntity document = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(sourceId, externalId, "Doc.pdf", "application/pdf", sourceVersion, null,
                        "ACTIVE", "PENDING", null));
        documentShareJpaRepository.saveAndFlush(
                new DocumentShareEntity(ownerSubject, sourceId, document.getId(), "INTERNAL", "VIEW", Instant.now()));
        return document.getId();
    }
}
