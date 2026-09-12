package com.sdv.rag.infrastructure.persistence.repository;

import com.sdv.rag.infrastructure.persistence.entity.DocumentEmbeddingEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DocumentEmbeddingJpaRepository}(F-BE-107, V006) 검증 - pgvector
 * {@code embedding} 컬럼 왕복(이 Backend 최초의 pgvector Java 매핑,
 * {@link DocumentEmbeddingEntity} Javadoc 참고), 원자적 Generation 교체(동시성
 * 포함), 삭제 전 입력 검증, 문서 삭제, Generation Uniqueness, 그리고 허용된
 * 문서 범위 안에서의 실제 Cosine Distance 순서 검색.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
@Transactional
class DocumentEmbeddingJpaRepositoryTest {

    @Autowired
    private DocumentEmbeddingJpaRepository repository;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void savedEmbeddingVectorRoundTripsExactly() {
        long documentId = createDocument();
        String vector = vectorOf(0.1f);

        DocumentEmbeddingEntity saved = repository.saveAndFlush(entity(documentId, 0, vector));

        DocumentEmbeddingEntity found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getEmbedding()).startsWith("[0.1,").endsWith("]");
        assertThat(found.getLocatorType()).isEqualTo("PAGE");
        assertThat(found.getContentHmac()).doesNotContain(" "); // 원문이 아니라 Digest임을 최소한으로 확인
        assertThat(found.getModelVersion()).isEqualTo("bge-m3:567m");
    }

    @Test
    void replaceGenerationAtomicallyReplacesAllPriorRowsForTheDocument() {
        long documentId = createDocument();
        repository.replaceGeneration(documentId, List.of(entity(documentId, 0, vectorOf(0.1f))));
        assertThat(repository.findAll()).hasSize(1);

        repository.replaceGeneration(documentId,
                List.of(entity(documentId, 0, vectorOf(0.2f)), entity(documentId, 1, vectorOf(0.3f))));

        List<DocumentEmbeddingEntity> current = repository.findAll();
        assertThat(current).hasSize(2);
        assertThat(current).extracting(DocumentEmbeddingEntity::getChunkIndex).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void replaceGenerationWithAnEmptyListLeavesTheDocumentWithoutEmbeddings() {
        long documentId = createDocument();
        repository.replaceGeneration(documentId, List.of(entity(documentId, 0, vectorOf(0.1f))));

        repository.replaceGeneration(documentId, List.of());

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void deleteByDocumentIdRemovesOnlyThatDocumentsRows() {
        long documentA = createDocument();
        long documentB = createDocument();
        repository.save(entity(documentA, 0, vectorOf(0.1f)));
        repository.save(entity(documentB, 0, vectorOf(0.2f)));

        int deleted = repository.deleteByDocumentId(documentA);

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findAll()).extracting(DocumentEmbeddingEntity::getDocumentId)
                .containsExactly(documentB);
    }

    @Test
    void duplicateChunkIndexWithinTheSameGenerationIsRejected() {
        long documentId = createDocument();
        repository.saveAndFlush(entity(documentId, 0, vectorOf(0.1f)));

        assertThatThrownBy(() -> repository.saveAndFlush(entity(documentId, 0, vectorOf(0.2f))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // replaceGeneration 입력 검증 - 삭제 전에 실패하며, 기존 Generation은
    // 그대로 남는다.
    //
    // 아래 세 Test 모두, 실제로 던져지는 것은 우리 Validation 코드의
    // IllegalArgumentException이지만, Spring Data JPA Repository Proxy를
    // 거치며 Spring의 표준 예외 변환(EntityManagerFactoryUtils
    // .convertJpaAccessExceptionIfPossible)이 그것을
    // InvalidDataAccessApiUsageException으로 감싸는 것을 실제로 실행해
    // 확인했다 - 이 파일의 기존 Test(duplicateChunkIndexWithinTheSameGenerationIsRejected)가
    // DB Unique 제약 위반을 DataIntegrityViolationException으로 기대하는
    // 것과 같은 이유(Spring의 표준 DAO 예외 변환 계층)다.
    // ------------------------------------------------------------------

    @Test
    void replaceGenerationRejectsARowBelongingToADifferentDocument() {
        long documentId = createDocument();
        long otherDocumentId = createDocument();
        repository.replaceGeneration(documentId, List.of(entity(documentId, 0, vectorOf(0.1f))));

        List<DocumentEmbeddingEntity> invalidBatch = List.of(
                entity(documentId, 0, vectorOf(0.2f)),
                entity(otherDocumentId, 1, vectorOf(0.3f)));

        assertThatThrownBy(() -> repository.replaceGeneration(documentId, invalidBatch))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("documentId");

        assertPriorGenerationIntact(documentId, 1);
    }

    @Test
    void replaceGenerationRejectsAMixedGenerationBatch() {
        long documentId = createDocument();
        repository.replaceGeneration(documentId, List.of(entity(documentId, 0, vectorOf(0.1f), "v1")));

        List<DocumentEmbeddingEntity> invalidBatch = List.of(
                entity(documentId, 0, vectorOf(0.2f), "v2"),
                entity(documentId, 1, vectorOf(0.3f), "v3")); // 서로 다른 sourceVersion - 한 Generation이 아니다.

        assertThatThrownBy(() -> repository.replaceGeneration(documentId, invalidBatch))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("generation");

        assertPriorGenerationIntact(documentId, 1);
    }

    @Test
    void replaceGenerationRejectsDuplicateChunkIndexesWithinTheBatch() {
        long documentId = createDocument();
        repository.replaceGeneration(documentId, List.of(entity(documentId, 0, vectorOf(0.1f))));

        List<DocumentEmbeddingEntity> invalidBatch = List.of(
                entity(documentId, 0, vectorOf(0.2f)),
                entity(documentId, 0, vectorOf(0.3f))); // 같은 chunkIndex가 배치 안에서 중복.

        assertThatThrownBy(() -> repository.replaceGeneration(documentId, invalidBatch))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("chunkIndex");

        assertPriorGenerationIntact(documentId, 1);
    }

    private void assertPriorGenerationIntact(long documentId, int expectedRowCount) {
        List<DocumentEmbeddingEntity> rows = repository.findAll().stream()
                .filter(row -> row.getDocumentId().equals(documentId))
                .toList();
        assertThat(rows).as("an invalid batch must never touch the prior generation").hasSize(expectedRowCount);
    }

    // ------------------------------------------------------------------
    // 동시성 - Review에서 발견된 실제 결함: Embedding 행이 하나도 없을 때
    // 두 동시 replaceGeneration 호출이 서로 다른 Generation을 각각 남길 수
    // 있었다. 이 Test는 결정론적 Latch/Barrier로 그 교차점을 실제로
    // 재현/검증한다(Timing Sleep에 기대지 않는다) - 클래스 레벨
    // @Transactional을 이 Test에서만 해제해 실제 여러 DB Transaction이
    // 서로를 볼 수 있게 한다(그렇지 않으면 각 Thread가 다른 Connection에서
    // Commit되지 않은 문서 행을 보지 못하거나 Lock 대상 자체를 찾지 못한다).
    // ------------------------------------------------------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReplaceGenerationForTheSameDocumentNeverLeavesMixedOrMultipleGenerations() throws Exception {
        long documentId = createDocument();
        try {
            List<DocumentEmbeddingEntity> generationA = List.of(
                    entity(documentId, 0, vectorOf(0.1f), "vA"),
                    entity(documentId, 1, vectorOf(0.2f), "vA"));
            List<DocumentEmbeddingEntity> generationB = List.of(
                    entity(documentId, 0, vectorOf(0.3f), "vB"),
                    entity(documentId, 1, vectorOf(0.4f), "vB"),
                    entity(documentId, 2, vectorOf(0.5f), "vB"));

            // Holder(H) - 두 replaceGeneration 호출이 결국 요청할 바로 그
            // source_documents 행을 먼저 잠근다(Embedding 행이 아직 하나도
            // 없으므로, 잠글 대상이 없다면 애초에 이 결함이 재현되지 않는다 -
            // 이 Holder가 바로 그 "잠글 대상 없음" 상황에서도 직렬화가
            // 일어남을 증명하는 장치다).
            CountDownLatch holderHasLock = new CountDownLatch(1);
            CountDownLatch releaseHolder = new CountDownLatch(1);
            Thread holder = new Thread(() -> transactionTemplateForTest().executeWithoutResult(status -> {
                entityManager.find(SourceDocumentEntity.class, documentId, LockModeType.PESSIMISTIC_WRITE);
                holderHasLock.countDown();
                try {
                    releaseHolder.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            holder.start();
            assertThat(holderHasLock.await(10, TimeUnit.SECONDS)).isTrue();

            // A를 먼저 Lock 대기열에 세운다.
            CompletableFuture<Void> replaceA = CompletableFuture.runAsync(
                    () -> repository.replaceGeneration(documentId, generationA));
            Thread.sleep(300);
            assertThat(replaceA.isDone())
                    .as("replaceGeneration(A) must be blocked on the pre-held source_documents lock")
                    .isFalse();

            // B를 그 뒤에 세운다 - A와 B 모두 아직 Holder의 Lock 때문에 대기 중이다.
            CompletableFuture<Void> replaceB = CompletableFuture.runAsync(
                    () -> repository.replaceGeneration(documentId, generationB));
            Thread.sleep(300);
            assertThat(replaceB.isDone())
                    .as("replaceGeneration(B) must also be blocked on the same lock")
                    .isFalse();

            // Holder를 푼다 - A와 B 중 하나가 Lock을 받아 자신의 Delete+Insert를
            // 전부 마치고 Commit할 때까지, 나머지 하나는 계속 대기해야 한다
            // (그래야 "Embedding 행이 없어서 서로를 못 막는" 원래 결함이
            // 재현되지 않는다).
            releaseHolder.countDown();
            holder.join(10_000);

            replaceA.get(15, TimeUnit.SECONDS);
            replaceB.get(15, TimeUnit.SECONDS);

            List<DocumentEmbeddingEntity> finalRows = repository.findAll().stream()
                    .filter(row -> row.getDocumentId().equals(documentId))
                    .toList();
            Set<String> distinctSourceVersions = finalRows.stream()
                    .map(DocumentEmbeddingEntity::getSourceVersion)
                    .collect(Collectors.toSet());
            assertThat(distinctSourceVersions)
                    .as("exactly one generation must survive - never a mix of vA and vB")
                    .hasSize(1);
            assertThat(finalRows.size())
                    .as("the surviving row count must match exactly one whole generation, not a partial mix")
                    .isIn(generationA.size(), generationB.size());
        } finally {
            // NOT_SUPPORTED이므로 이 Test의 변경은 자동 Rollback되지 않는다 -
            // 다른 Test의 repository.findAll() 절대 개수 단언에 영향을 주지
            // 않도록 명시적으로 정리한다(FK ON DELETE CASCADE로 Embedding
            // 행도 함께 지워진다).
            sourceDocumentJpaRepository.deleteById(documentId);
        }
    }

    private TransactionTemplate transactionTemplateForTest() {
        return new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // searchAllowed - 실제로 방향이 다른 Vector로 Cosine 순서 자체를 검증한다
    // (이전 버전의 결함: [1,0,...]과 [5,0,...]은 방향이 같아 Cosine Distance가
    // 0으로 동일한 Tie였다 - 순서를 증명하지 못했다).
    // ------------------------------------------------------------------

    @Test
    void searchAllowedOrdersByCosineDistanceAndNeverReturnsDocumentsOutsideTheAllowedSet() {
        long allowedDoc = createDocument();
        long deniedDoc = createDocument();
        String query = unitVector(0); // [1,0,0,...]

        // allowedDoc: chunk 0은 Query와 방향이 완전히 같다(Cosine Distance 0,
        // 가장 가깝다) - chunk 1은 Query와 직교한다(Cosine Distance 1, 더 멀다).
        repository.saveAndFlush(entity(allowedDoc, 0, unitVector(0))); // near - 완전히 같은 방향
        repository.saveAndFlush(entity(allowedDoc, 1, unitVector(1))); // far - 직교(90도)
        // deniedDoc: Query와 완전히 같은 방향(전체 후보 중 가장 가깝다) -
        // 그럼에도 allowedDocumentIds 밖이므로 결과에 나타나면 안 된다.
        repository.saveAndFlush(entity(deniedDoc, 0, unitVector(0)));

        List<DocumentEmbeddingEntity> results = repository.searchAllowed(List.of(allowedDoc), query, 10);

        assertThat(results).extracting(DocumentEmbeddingEntity::getDocumentId).containsOnly(allowedDoc);
        // Tie가 아니다 - chunk 0(방향 일치, Distance 0)이 chunk 1(직교, Distance 1)보다
        // 반드시 먼저 나와야 한다.
        assertThat(results).extracting(DocumentEmbeddingEntity::getChunkIndex).containsExactly(0, 1);
    }

    @Test
    void searchAllowedWithAnEmptyAllowedSetReturnsNoCandidates() {
        long documentId = createDocument();
        repository.saveAndFlush(entity(documentId, 0, unitVector(0)));

        List<DocumentEmbeddingEntity> results = repository.searchAllowed(List.of(), unitVector(0), 10);

        assertThat(results).isEmpty();
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

    private static DocumentEmbeddingEntity entity(long documentId, int chunkIndex, String vector) {
        return entity(documentId, chunkIndex, vector, "v1");
    }

    private static DocumentEmbeddingEntity entity(long documentId, int chunkIndex, String vector,
            String sourceVersion) {
        return new DocumentEmbeddingEntity(documentId, chunkIndex, "PAGE", "1", vector, sourceVersion,
                "0".repeat(64), "1", "bge-m3:567m", Instant.now());
    }

    /** pgvector 텍스트 형식({@code "[firstValue,0,0,...]"}, 나머지 1023차원은 0) 리터럴을 만든다. */
    private static String vectorOf(float firstValue) {
        StringBuilder sb = new StringBuilder("[").append(firstValue);
        for (int i = 1; i < DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS; i++) {
            sb.append(",0");
        }
        return sb.append(']').toString();
    }

    /** 지정한 차원만 1이고 나머지는 0인 단위(Unit) Vector - 서로 다른 축은 방향이 완전히 직교한다(Cosine Distance 1). */
    private static String unitVector(int axisIndex) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < DocumentEmbeddingEntity.EMBEDDING_DIMENSIONS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i == axisIndex ? '1' : '0');
        }
        return sb.append(']').toString();
    }
}
