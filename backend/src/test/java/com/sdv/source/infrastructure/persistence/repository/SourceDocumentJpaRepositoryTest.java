package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-BE-032/036 검증: (source_id, source_document_id) 유일성(V001
 * uq_source_document 보존), state ACTIVE/DELETED 제약(V004
 * chk_source_document_state), 기본 문서 조회의 DELETED 제외, Disconnect의
 * 일괄 DELETED 전이, RAG 색인 후보 조회.
 */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SourceDocumentJpaRepositoryTest {

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;

    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;

    @Autowired
    private DocumentShareJpaRepository documentShareJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void duplicateExternalDocumentIdWithinSameSourceIsRejected() {
        Long sourceId = persistSource("owner-doc-dup");
        sourceDocumentJpaRepository.saveAndFlush(document(sourceId, "external-doc-1", "ACTIVE"));

        assertThatThrownBy(() ->
                sourceDocumentJpaRepository.saveAndFlush(document(sourceId, "external-doc-1", "ACTIVE")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameExternalDocumentIdInDifferentSourcesIsAllowed() {
        Long sourceA = persistSource("owner-doc-multi-a");
        Long sourceB = persistSource("owner-doc-multi-b");

        SourceDocumentEntity savedA = sourceDocumentJpaRepository.saveAndFlush(
                document(sourceA, "shared-external-id", "ACTIVE"));
        SourceDocumentEntity savedB = sourceDocumentJpaRepository.saveAndFlush(
                document(sourceB, "shared-external-id", "ACTIVE"));

        assertThat(savedA.getId()).isNotEqualTo(savedB.getId());
    }

    @Test
    void onlyActiveAndDeletedStateValuesAreAccepted() {
        Long sourceId = persistSource("owner-doc-state");

        sourceDocumentJpaRepository.saveAndFlush(document(sourceId, "state-active", "ACTIVE"));
        entityManager.clear();
        sourceDocumentJpaRepository.saveAndFlush(document(sourceId, "state-deleted", "DELETED"));
        entityManager.clear();

        assertThatThrownBy(() ->
                sourceDocumentJpaRepository.saveAndFlush(document(sourceId, "state-invalid", "SYNCED")))
                .as("obsolete lifecycle values (SYNCED/READY/STALE/FAILED) must be rejected, not normalized")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void defaultDocumentQueryExcludesDeletedDocuments() {
        Long sourceId = persistSource("owner-doc-default-query");
        sourceDocumentJpaRepository.saveAndFlush(document(sourceId, "visible-doc", "ACTIVE"));
        sourceDocumentJpaRepository.saveAndFlush(document(sourceId, "hidden-doc", "DELETED"));
        entityManager.clear();

        List<SourceDocumentEntity> visible = sourceDocumentJpaRepository.findBySourceIdAndStateNot(sourceId, "DELETED");

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).getSourceDocumentId()).isEqualTo("visible-doc");
    }

    @Test
    void markAllActiveAsDeletedForSourceIsIdempotentAndScopedToOneSource() {
        Long sourceA = persistSource("owner-doc-mark-a");
        Long sourceB = persistSource("owner-doc-mark-b");
        sourceDocumentJpaRepository.saveAndFlush(document(sourceA, "doc-1", "ACTIVE"));
        sourceDocumentJpaRepository.saveAndFlush(document(sourceA, "doc-2", "ACTIVE"));
        sourceDocumentJpaRepository.saveAndFlush(document(sourceB, "doc-3", "ACTIVE"));
        entityManager.clear();

        int updated = sourceDocumentJpaRepository.markAllActiveAsDeletedForSource(sourceA);
        assertThat(updated).isEqualTo(2);
        entityManager.clear();

        assertThat(sourceDocumentJpaRepository.findBySourceIdAndStateNot(sourceA, "DELETED")).isEmpty();
        assertThat(sourceDocumentJpaRepository.findBySourceIdAndStateNot(sourceB, "DELETED")).hasSize(1);

        // idempotent: calling again marks nothing further (already-DELETED rows are untouched)
        int updatedAgain = sourceDocumentJpaRepository.markAllActiveAsDeletedForSource(sourceA);
        assertThat(updatedAgain).isZero();
    }

    @Test
    void findIndexEligibleIdsReturnsOnlyActiveAndIndexedDocuments() {
        Long sourceId = persistSource("owner-doc-eligible");
        SourceDocumentEntity eligible = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(sourceId, "eligible-doc", "Eligible.txt", "text/plain", null, null,
                        "ACTIVE", "INDEXED", null));
        sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(sourceId, "pending-doc", "Pending.txt", "text/plain", null, null,
                        "ACTIVE", "PENDING", null));
        entityManager.clear();

        List<Long> eligibleIds = sourceDocumentJpaRepository.findIndexEligibleIds();

        assertThat(eligibleIds).contains(eligible.getId());
        assertThat(eligibleIds).hasSize(1);
    }

    @Test
    void findBySourceAndSourceDocIdFindsRegardlessOfState() {
        Long sourceId = persistSource("owner-doc-lookup");
        sourceDocumentJpaRepository.saveAndFlush(document(sourceId, "lookup-doc", "DELETED"));
        entityManager.clear();

        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, "lookup-doc")).isPresent();
        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, "missing-doc")).isEmpty();
    }

    /**
     * M11 후속 교정 검증 - {@link SourceDocumentJpaRepository#updateIndexStatusIfCurrent}는
     * 현재 {@code source_version}과 정확히 일치하고 아직 {@code INDEXED}가 아닐 때만
     * 실제로 적용된다.
     */
    @Test
    void updateIndexStatusIfCurrentAppliesOnlyWhenVersionMatchesAndNotAlreadyIndexed() {
        Long sourceId = persistSource("owner-doc-fencing-current");
        SourceDocumentEntity document = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(sourceId, "fencing-doc", "Doc.pdf", "application/pdf", "v1", null, "ACTIVE",
                        "PENDING", null));
        Long documentId = document.getId();
        entityManager.clear();

        int mismatchedVersion = sourceDocumentJpaRepository.updateIndexStatusIfCurrent(documentId,
                "SKIPPED_NO_TEXT", "AI_SERVICE_NO_TEXT", "v2-not-current");
        assertThat(mismatchedVersion).as("a stale expected version must not be applied").isZero();
        entityManager.clear();
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .isEqualTo("PENDING");

        int matched = sourceDocumentJpaRepository.updateIndexStatusIfCurrent(documentId, "SKIPPED_NO_TEXT",
                "AI_SERVICE_NO_TEXT", "v1");
        assertThat(matched).isEqualTo(1);
        entityManager.clear();
        SourceDocumentEntity afterSkip = sourceDocumentJpaRepository.findById(documentId).orElseThrow();
        assertThat(afterSkip.getIndexStatus()).isEqualTo("SKIPPED_NO_TEXT");
        assertThat(afterSkip.getIndexReason()).isEqualTo("AI_SERVICE_NO_TEXT");

        // 이미 INDEXED가 된 뒤에는(같은 Version이라도) 낡은 SKIPPED/FAILED 시도가 덮어쓰지 못한다.
        sourceDocumentJpaRepository.updateIndexStatus(documentId, "INDEXED", null);
        entityManager.clear();
        int afterIndexed = sourceDocumentJpaRepository.updateIndexStatusIfCurrent(documentId, "SKIPPED_UNSUPPORTED",
                "AI_SERVICE_UNSUPPORTED_FORMAT", "v1");
        assertThat(afterIndexed).as("an obsolete SKIPPED write must never downgrade an already-INDEXED document")
                .isZero();
        entityManager.clear();
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .isEqualTo("INDEXED");
    }

    /**
     * M11 후속 교정 검증 - {@link SourceDocumentJpaRepository#findActivelySharedForReconnectScheduling}
     * (재연결 색인 스케줄링)은 정확히 이 Source에 속하고, 지금 활성(미철회)+관리자
     * 미차단 공유가 있는 ACTIVE 문서만 반환한다 - 철회/차단된 공유와 다른 Source의
     * 문서는 제외된다("preserving restrictions and revoked shares").
     */
    @Test
    void findActivelySharedForReconnectSchedulingReturnsOnlyCurrentlyEligibleDocumentsForThatSource() {
        Long sourceId = persistSource("owner-reconnect-scheduling");
        Long otherSourceId = persistSource("owner-reconnect-scheduling-other");

        SourceDocumentEntity sharedActive = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(sourceId, "shared-active", "Shared.pdf", "application/pdf", "v1", null,
                        "ACTIVE", "PENDING", null));
        SourceDocumentEntity revokedShareDoc = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(sourceId, "revoked-share", "Revoked.pdf", "application/pdf", "v1", null,
                        "ACTIVE", "PENDING", null));
        SourceDocumentEntity blockedShareDoc = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(sourceId, "blocked-share", "Blocked.pdf", "application/pdf", "v1", null,
                        "ACTIVE", "PENDING", null));
        SourceDocumentEntity otherSourceDoc = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(otherSourceId, "other-source-shared", "Other.pdf", "application/pdf", "v1",
                        null, "ACTIVE", "PENDING", null));

        DocumentShareEntity activeShare = new DocumentShareEntity("owner-reconnect-scheduling", sourceId,
                sharedActive.getId(), "INTERNAL", "VIEW", Instant.now());
        documentShareJpaRepository.saveAndFlush(activeShare);

        DocumentShareEntity revokedShare = new DocumentShareEntity("owner-reconnect-scheduling", sourceId,
                revokedShareDoc.getId(), "INTERNAL", "VIEW", Instant.now());
        revokedShare.revoke(Instant.now());
        documentShareJpaRepository.saveAndFlush(revokedShare);

        DocumentShareEntity blockedShare = new DocumentShareEntity("owner-reconnect-scheduling", sourceId,
                blockedShareDoc.getId(), "INTERNAL", "VIEW", Instant.now());
        blockedShare.applyAdminBlock(true, "policy violation", Instant.now());
        documentShareJpaRepository.saveAndFlush(blockedShare);

        DocumentShareEntity otherSourceShare = new DocumentShareEntity("owner-reconnect-scheduling-other",
                otherSourceId, otherSourceDoc.getId(), "INTERNAL", "VIEW", Instant.now());
        documentShareJpaRepository.saveAndFlush(otherSourceShare);
        entityManager.clear();

        Slice<SourceDocumentEntity> result = sourceDocumentJpaRepository
                .findActivelySharedForReconnectScheduling(sourceId, PageRequest.of(0, 200));

        assertThat(result.getContent()).extracting(SourceDocumentEntity::getSourceDocumentId)
                .containsExactly("shared-active");
    }

    @Test
    void ownerPickerSearchFiltersBeforePagingAndTreatsLikeMetacharactersLiterally() {
        Long sourceId = persistSource("owner-picker-search");
        for (int index = 0; index < 30; index++) {
            sourceDocumentJpaRepository.save(document(sourceId, "noise-" + index, "ACTIVE"));
        }
        sourceDocumentJpaRepository.save(documentNamed(sourceId, "literal-percent", "Budget%_2026\\final.txt"));
        sourceDocumentJpaRepository.save(documentNamed(sourceId, "case-match", "QUARTERLY Report.txt"));
        sourceDocumentJpaRepository.flush();
        entityManager.clear();

        Slice<SourceDocumentEntity> literal = sourceDocumentJpaRepository.findOwnedForPicker(
                "owner-picker-search", sourceId, true, "%budget\\%\\_2026\\\\final%", PageRequest.of(0, 10));
        Slice<SourceDocumentEntity> caseInsensitive = sourceDocumentJpaRepository.findOwnedForPicker(
                "owner-picker-search", sourceId, true, "%quarterly report%", PageRequest.of(0, 10));

        assertThat(literal.getContent()).extracting(SourceDocumentEntity::getName)
                .containsExactly("Budget%_2026\\final.txt");
        assertThat(caseInsensitive.getContent()).extracting(SourceDocumentEntity::getName)
                .containsExactly("QUARTERLY Report.txt");
    }

    @Test
    void ownerPickerSearchKeepsStableNameThenIdOrderingAndDoesNotCrossOwners() {
        Long sourceId = persistSource("owner-picker-order");
        Long otherSourceId = persistSource("other-picker-order");
        SourceDocumentEntity first = sourceDocumentJpaRepository.save(
                documentNamed(sourceId, "same-1", "same.txt"));
        SourceDocumentEntity second = sourceDocumentJpaRepository.save(
                documentNamed(sourceId, "same-2", "same.txt"));
        sourceDocumentJpaRepository.save(documentNamed(otherSourceId, "other", "same.txt"));
        sourceDocumentJpaRepository.flush();
        entityManager.clear();

        Slice<SourceDocumentEntity> result = sourceDocumentJpaRepository.findOwnedForPicker(
                "owner-picker-order", sourceId, false, "", PageRequest.of(0, 10,
                        org.springframework.data.domain.Sort.by("name").and(org.springframework.data.domain.Sort.by("id"))));

        assertThat(result.getContent()).extracting(SourceDocumentEntity::getId)
                .containsExactly(first.getId(), second.getId());
    }

    private Long persistSource(String ownerSubject) {
        SourceConnectionEntity source = sourceConnectionJpaRepository.saveAndFlush(
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "INCREMENTAL", ownerSubject));
        return source.getId();
    }

    private static SourceDocumentEntity document(Long sourceId, String externalId, String state) {
        return new SourceDocumentEntity(sourceId, externalId, "Doc " + externalId, "text/plain", null, null,
                state, "PENDING", null);
    }

    private static SourceDocumentEntity documentNamed(Long sourceId, String externalId, String name) {
        return new SourceDocumentEntity(sourceId, externalId, name, "text/plain", null, null,
                "ACTIVE", "PENDING", null);
    }
}
