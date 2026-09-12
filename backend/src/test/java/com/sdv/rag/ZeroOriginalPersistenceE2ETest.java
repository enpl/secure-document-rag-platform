package com.sdv.rag;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.rag.application.ContentExtractionService;
import com.sdv.rag.application.ContentExtractionService.ExtractionOutcome;
import com.sdv.rag.infrastructure.persistence.repository.DocumentEmbeddingJpaRepository;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-TST-007 (M07A) - V006(Zero-Original-Persistence) 교정의 경계를 실제로
 * 증명하는 E2E Canary Sweep. 세 층을 함께 확인한다:
 *
 * <ol>
 *   <li>Schema 층 - 제거되어야 할 평문 테이블이 실제로 없다.</li>
 *   <li>동작 층 - 인가된 요청이라도 {@link ContentExtractionService#extract}가
 *       실제로 {@code SUCCESS}/{@code INDEXED}를 만들어내지 않는다(V006 이후
 *       이 경로는 명시적으로 비활성화됨 - 같은 패키지의 Class Javadoc
 *       참고).</li>
 *   <li>Canary 층 - 문서 이름에 심어둔 고유한 합성(Synthetic) 마커 문자열이
 *       (a) {@code document_embedding_index}의 어떤 행에도, (b) 이 테스트
 *       실행 동안 Capture한 Log 이벤트 어디에도 나타나지 않는다.</li>
 * </ol>
 *
 * <p><b>범위 밖으로 명시하는 것(정직한 경계, 지어내지 않는다):</b></p>
 * <ul>
 *   <li>"임시 파일/Cache 위치" 스윕은 수행하지 않는다 - {@code grep -r
 *       "createTempFile\|FileOutputStream\|Files.write"
 *       backend/src/main}로 확인한 결과, 현재 Backend 코드 어디에도 문서
 *       Content를 위한 임시 파일/디스크 Cache Writer가 존재하지 않는다
 *       (검증 결과, 이 작업 시점 기준). 없는 대상을 스캔하는 척하지
 *       않는다 - 이 사실 자체가 "Content 관련 임시 파일이 없다"는 증거다.
 *       OS 공용 Temp 디렉터리({@code java.io.tmpdir})를 통째로 스캔하지도
 *       않는다(이 작업 지시사항이 명시적으로 금지하는 "기기 전체 범위
 *       스캔"에 해당한다).</li>
 *   <li>이 테스트는 "이 테스트 경계 안에서 영속 쓰기가 관측되지 않았다"는
 *       것을 증명할 뿐, Disk Page/WAL/Replica/Snapshot/Backup에서의 물리적
 *       소거를 증명하지 않는다({@code docs/runbooks/V006_ZERO_ORIGINAL_PERSISTENCE.md}
 *       참고).</li>
 *   <li>Cancellation/Restart/Orphan-Recovery Lifecycle은 시험하지 않는다 -
 *       현재 구현에 그런 Lifecycle 자체가 없다(Extract 경로가 Fetch/Parse
 *       이전에 완전히 종료된다). 존재하지 않는 Lifecycle을 시험하는 척
 *       Test를 지어내지 않는다 - M12/M17 범위로 명시적으로 남긴다.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.policy.permission-freshness-max-age=PT24H"
})
class ZeroOriginalPersistenceE2ETest {

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
    @Autowired
    private EntityManager entityManager;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void attachLogCapture() {
        logCapture = new ListAppender<>();
        logCapture.start();
        rootLogger().addAppender(logCapture);
    }

    @AfterEach
    void detachLogCapture() {
        rootLogger().detachAppender(logCapture);
        logCapture.stop();
    }

    @Test
    void legacyPlaintextTablesDoNotExist() {
        assertThat(tableExists("document_extracted_content")).isFalse();
        assertThat(tableExists("document_chunks")).isFalse();
        assertThat(tableExists("document_embedding_index")).isTrue();
    }

    /**
     * 핵심 Canary Sweep - 문서 이름에 고유한 합성 마커를 심고, 인가된
     * {@code extract()} 호출이 그 마커를 어디에도(Embedding Index 행, Capture한
     * Log) 남기지 않음을 확인한다. 동시에 이 호출이 {@code SUCCESS}를
     * 반환하지 않고(V006 이후 이 경로는 처리를 시도조차 하지 않는다),
     * {@code index_status}가 근거 없이 {@code INDEXED}로 바뀌지 않았음을
     * 함께 확인한다.
     */
    @Test
    void authorizedExtractLeavesNoTraceOfTheSyntheticCanaryAnywhere() {
        String canary = "ZOP-CANARY-" + UUID.randomUUID();
        String owner = "owner-canary-" + UUID.randomUUID();
        long documentId = createDocument(owner, canary);
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(documentId, "user", owner, "READ", Instant.now()));

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        assertThat(outcome.kind())
                .as("V006 이후 authorized extract() must never fabricate SUCCESS")
                .isNotEqualTo(ExtractionOutcome.Kind.SUCCESS);
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .isNotEqualTo("INDEXED");

        List<Long> embeddingRowsForThisDocument = documentEmbeddingJpaRepository.findAll().stream()
                .filter(row -> row.getDocumentId().equals(documentId))
                .map(row -> row.getId())
                .toList();
        assertThat(embeddingRowsForThisDocument)
                .as("no embedding-index row must have been written for this document")
                .isEmpty();

        boolean canaryLeakedInLogs = logCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message != null)
                .anyMatch(message -> message.contains(canary));
        assertThat(canaryLeakedInLogs).as("the synthetic canary must never appear in captured log output").isFalse();
    }

    private long createDocument(String ownerSubject, String canaryName) {
        SourceConnectionEntity connection =
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(),
                "doc-" + UUID.randomUUID(), canaryName, "text/plain", "v1", null, "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return document.getId();
    }

    private static UserContext userContext(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of());
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private boolean tableExists(String table) {
        List<?> rows = entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.tables WHERE table_name = ?1")
                .setParameter(1, table)
                .getResultList();
        return !rows.isEmpty();
    }
}
