package com.sdv.source.infrastructure.persistence.repository;

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

    private Long persistSource(String ownerSubject) {
        SourceConnectionEntity source = sourceConnectionJpaRepository.saveAndFlush(
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "INCREMENTAL", ownerSubject));
        return source.getId();
    }

    private static SourceDocumentEntity document(Long sourceId, String externalId, String state) {
        return new SourceDocumentEntity(sourceId, externalId, "Doc " + externalId, "text/plain", null, null,
                state, "PENDING", null);
    }
}
