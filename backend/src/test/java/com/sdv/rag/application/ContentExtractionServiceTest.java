package com.sdv.rag.application;

import com.sdv.audit.infrastructure.persistence.repository.AuditLogJpaRepository;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.rag.application.ContentExtractionService.ExtractionOutcome;
import com.sdv.rag.domain.ExtractedLocation;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.rag.infrastructure.persistence.entity.DocumentExtractedContentEntity;
import com.sdv.rag.infrastructure.persistence.repository.DocumentExtractedContentJpaRepository;
import com.sdv.source.application.SourceConnectionService;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourcePermission;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M06 중앙 조율({@link ContentExtractionService}) 통합 테스트 - 실제
 * Testcontainers PostgreSQL과 M05 {@code EffectivePermissionService}를 그대로
 * 쓰되, Source Byte Fetch({@link DocumentSourceConnector})와 Python 호출
 * ({@link DocumentParsingClient})은 Test Double로 대체한다(실제 AI Service/실제
 * Drive를 띄우지 않는다 - "대표 성공 경로"이지 End-to-End 검증이 아니다).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ContentExtractionServiceTest.Doubles.class})
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.policy.permission-freshness-max-age=PT24H"
})
class ContentExtractionServiceTest {

    private static final String OWNER_GROUP = Role.USER.name();

    @Autowired
    private ContentExtractionService contentExtractionService;
    @Autowired
    private SourceConnectionService sourceConnectionService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private SourcePermissionJpaRepository sourcePermissionJpaRepository;
    @Autowired
    private DocumentExtractedContentJpaRepository extractedContentJpaRepository;
    @Autowired
    private AuditLogJpaRepository auditLogJpaRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private FakeDocumentSourceConnector fakeConnector;
    @Autowired
    private ControllableDocumentParsingClient controllableParsingClient;

    @AfterEach
    void resetDoubles() {
        fakeConnector.reset();
        controllableParsingClient.reset();
    }

    @Test
    void successfulExtractionIsPublishedAndIndexStatusIsUntouched() {
        String owner = "owner-success-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("hello world".getBytes());
        controllableParsingClient.setBehavior(content -> ParseOutcome.success("plaintext", "1", "1",
                new String(content), List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, content.length))));

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.SUCCESS);
        DocumentExtractedContentEntity row = extractedContentJpaRepository.findById(documentId).orElseThrow();
        assertThat(row.isPublished()).isTrue();
        assertThat(row.getNormalizedText()).isEqualTo("hello world");
        assertThat(row.getSourceVersion()).isEqualTo("v1");
        assertThat(row.getContentHash()).isNotBlank();
        // 성공은 INDEXED가 아니다 - index_status는 V003 기본값 PENDING 그대로 유지된다.
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .isEqualTo("PENDING");
    }

    @Test
    void deniedCallerNeverFetchesOrChangesAnyState() {
        String owner = "owner-denied-" + unique();
        String attacker = "attacker-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(documentId, owner);

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(attacker), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.DENIED);
        assertThat(fakeConnector.fetchCount()).isZero();
        assertThat(extractedContentJpaRepository.findById(documentId)).isEmpty();
    }

    @Test
    void staleAclEvidenceIsDeniedBeforeAnyFetch() {
        String owner = "owner-stale-acl-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        sourcePermissionJpaRepository.saveAndFlush(new SourcePermissionEntity(documentId, "user", owner, "READ",
                Instant.now().minus(Duration.ofHours(48))));

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.DENIED);
        assertThat(fakeConnector.fetchCount()).isZero();
    }

    @Test
    void unsupportedFormatRecordsSkippedUnsupportedAndPublishesNoContent() {
        String owner = "owner-unsupported-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("whatever".getBytes());
        controllableParsingClient.setBehavior(
                content -> ParseOutcome.failure(ParseOutcomeKind.UNSUPPORTED_FORMAT, "unsupported or mismatched format"));

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.FAILED);
        SourceDocumentEntity document = sourceDocumentJpaRepository.findById(documentId).orElseThrow();
        assertThat(document.getIndexStatus()).isEqualTo("SKIPPED_UNSUPPORTED");
        assertThat(document.getIndexReason()).doesNotContain("whatever");
        assertThat(extractedContentJpaRepository.findById(documentId).map(DocumentExtractedContentEntity::isPublished))
                .contains(false);
    }

    @Test
    void noTextOutcomeRecordsSkippedNoText() {
        String owner = "owner-notext-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("image bytes".getBytes());
        controllableParsingClient.setBehavior(
                content -> ParseOutcome.failure(ParseOutcomeKind.NO_TEXT, "no extractable text"));

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.FAILED);
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .isEqualTo("SKIPPED_NO_TEXT");
    }

    @Test
    void nullSourceVersionFailsClosedEvenWhenEverythingElseSucceeds() {
        String owner = "owner-nullversion-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", null);
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("hello".getBytes());
        controllableParsingClient.setBehavior(content -> ParseOutcome.success("plaintext", "1", "1", "hello",
                List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, 5))));

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        // M06 후속 교정: 우리가 여전히 Claim을 소유한 채 재검증에 실패했으므로
        // (다른 시도가 재점유한 것이 아니다) - 단순 SUPERSEDED가 아니라 실제로
        // 기록된 FAILED다(Claim도 즉시 해제된다 - 아래 별도 테스트가 증명한다).
        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.FAILED);
        assertThat(extractedContentJpaRepository.findById(documentId).map(DocumentExtractedContentEntity::isPublished))
                .contains(false);
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .isEqualTo("FAILED");
    }

    @Test
    void retryingASuccessfulExtractionUpdatesTheSameRowRatherThanDuplicating() {
        String owner = "owner-retry-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("first".getBytes());
        controllableParsingClient.setBehavior(content -> ParseOutcome.success("plaintext", "1", "1",
                new String(content), List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, content.length))));

        assertThat(contentExtractionService.extract(userContext(owner), documentId).kind())
                .isEqualTo(ExtractionOutcome.Kind.SUCCESS);

        fakeConnector.setContent("second".getBytes());
        assertThat(contentExtractionService.extract(userContext(owner), documentId).kind())
                .isEqualTo(ExtractionOutcome.Kind.SUCCESS);

        // document_extracted_content.document_id는 PK다 - 같은 문서에 대한 두 번째
        // 성공이 별도 행을 만들 수 없다(구조적으로 중복 불가). 실제 검증 대상은
        // "최신 내용으로 갱신됐는가"다(Retry가 Upsert이지 append가 아님을 증명).
        assertThat(extractedContentJpaRepository.findById(documentId).orElseThrow().getNormalizedText())
                .isEqualTo("second");
    }

    /**
     * M06 후속 교정 - 핵심 회귀. 이전에는 "발행된 결과는 실패한 재시도로
     * 지워지지 않는다"고 잘못 보존했다 - 이는 같은 버전에 대한 재시도가
     * 실패했을 때도 예전 텍스트가 여전히 유효한 것처럼(published_at +
     * source_version 일치 + ACTIVE라는 암묵적 비교로) 남는 결함이었다.
     * 이제는 인가된(Claim을 여전히 소유한) 현재 시도가 실패하면 - Source
     * Version이 바뀌었든 안 바뀌었든 - 이전 발행 결과를 명시적으로
     * 무효화한다.
     */
    @Test
    void authorizedFailureExplicitlyInvalidatesThePreviouslyPublishedResultAfterAVersionChange() {
        String owner = "owner-newer-fails-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("v1 content".getBytes());
        controllableParsingClient.setBehavior(content -> ParseOutcome.success("plaintext", "1", "1",
                new String(content), List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, content.length))));
        assertThat(contentExtractionService.extract(userContext(owner), documentId).kind())
                .isEqualTo(ExtractionOutcome.Kind.SUCCESS);

        // Source가 새 버전으로 바뀐 상황을 흉내낸다(Sync는 M10 범위 밖 - 직접 갱신) -
        // 경쟁하는 다른 Claim 시도는 전혀 없다("Also test A failing after the live
        // source version changes even without a replacement claim").
        setSourceVersion(documentId, "v2");
        controllableParsingClient.setBehavior(content -> ParseOutcome.failure(ParseOutcomeKind.FAILED, "parse error"));

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.FAILED);
        DocumentExtractedContentEntity row = extractedContentJpaRepository.findById(documentId).orElseThrow();
        // v1의 발행된 결과가 명시적으로 무효화됐다 - "이제 SUCCESS는 없다"가 아니라
        // 행 전체가 미발행 상태로 되돌아간다(V005 CHECK 제약의 "미발행" 분기).
        assertThat(row.isPublished()).isFalse();
        assertThat(row.getNormalizedText()).isNull();
        assertThat(row.getSourceVersion()).isNull();
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .isEqualTo("FAILED");
    }

    /** "SAME-version retry failure" - 버전이 전혀 바뀌지 않았어도 실패한 재시도는 이전 성공 결과를 명시적으로 지운다. */
    @Test
    void sameVersionRetryFailureAlsoInvalidatesThePreviouslyPublishedResult() {
        String owner = "owner-same-version-fails-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("first content".getBytes());
        controllableParsingClient.setBehavior(content -> ParseOutcome.success("plaintext", "1", "1",
                new String(content), List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, content.length))));
        assertThat(contentExtractionService.extract(userContext(owner), documentId).kind())
                .isEqualTo(ExtractionOutcome.Kind.SUCCESS);

        // 버전은 그대로 v1이다 - 순수하게 재시도가 실패하는 경우.
        controllableParsingClient.setBehavior(content -> ParseOutcome.failure(ParseOutcomeKind.FAILED, "parse error"));

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.FAILED);
        assertThat(extractedContentJpaRepository.findById(documentId).orElseThrow().isPublished()).isFalse();
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .isEqualTo("FAILED");
    }

    /**
     * M06 후속 교정. 이전에는 성공이 index_status를 전혀 건드리지 않아,
     * FAILED였던 문서가 성공적으로 재추출돼도 FAILED로 남았다 - 이제는
     * 성공한 발행이 PENDING(+ Reason null)으로 명시적으로 재설정한다
     * (INDEXED를 쓰지는 않는다 - 성공은 INDEXED가 아니다).
     */
    @Test
    void successfulRetryAfterAFailureResetsIndexStatusFromFailedToPending() {
        String owner = "owner-recover-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("bad".getBytes());
        controllableParsingClient.setBehavior(
                content -> ParseOutcome.failure(ParseOutcomeKind.FAILED, "parse error"));
        assertThat(contentExtractionService.extract(userContext(owner), documentId).kind())
                .isEqualTo(ExtractionOutcome.Kind.FAILED);
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .isEqualTo("FAILED");

        fakeConnector.setContent("now it works".getBytes());
        controllableParsingClient.setBehavior(content -> ParseOutcome.success("plaintext", "1", "1",
                new String(content), List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, content.length))));

        ExtractionOutcome outcome = contentExtractionService.extract(userContext(owner), documentId);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.SUCCESS);
        SourceDocumentEntity document = sourceDocumentJpaRepository.findById(documentId).orElseThrow();
        assertThat(document.getIndexStatus()).isEqualTo("PENDING");
        assertThat(document.getIndexReason()).isNull();
    }

    /**
     * M06 후속 교정. 이전에는 Null/버전 불일치로 인한 발행 거부가 Claim을
     * 해제하지 않고 그냥 SUPERSEDED만 반환해, 다음 재시도가 Claim이 Stale
     * 판정될 때까지(최대 90초) 막힐 수 있었다 - 이제는 재검증 실패 시점에
     * 즉시 Claim을 해제하므로, 바로 다음 시도가 CONFLICT 없이 Claim을 얻을
     * 수 있다.
     */
    @Test
    void nullVersionRejectionReleasesTheClaimImmediatelyPermittingAnImmediateRetry() {
        String owner = "owner-immediate-retry-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", null);
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("hello".getBytes());
        controllableParsingClient.setBehavior(content -> ParseOutcome.success("plaintext", "1", "1", "hello",
                List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, 5))));

        ExtractionOutcome firstOutcome = contentExtractionService.extract(userContext(owner), documentId);
        assertThat(firstOutcome.kind()).isEqualTo(ExtractionOutcome.Kind.FAILED);
        assertThat(sourceDocumentJpaRepository.findById(documentId).orElseThrow().getIndexStatus())
                .isEqualTo("FAILED");

        // 90초를 기다리지 않고 즉시 재시도한다 - CONFLICT가 아니라(여전히 같은
        // 이유로) FAILED를 받아야 한다. CONFLICT를 받는다면 Claim이 해제되지
        // 않았다는 뜻이다.
        ExtractionOutcome secondOutcome = contentExtractionService.extract(userContext(owner), documentId);
        assertThat(secondOutcome.kind()).isNotEqualTo(ExtractionOutcome.Kind.CONFLICT);
    }

    @Test
    void concurrentAttemptsForTheSameDocumentAreExclusive() throws Exception {
        String owner = "owner-concurrent-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("content".getBytes());

        CountDownLatch parseStarted = new CountDownLatch(1);
        CountDownLatch releaseParse = new CountDownLatch(1);
        controllableParsingClient.setBehavior(content -> {
            parseStarted.countDown();
            try {
                releaseParse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ParseOutcome.success("plaintext", "1", "1", new String(content),
                    List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, content.length)));
        });

        CompletableFuture<ExtractionOutcome> first = CompletableFuture.supplyAsync(
                () -> contentExtractionService.extract(userContext(owner), documentId));
        assertThat(parseStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // 첫 시도가 아직 Parsing 중(Claim 보유)인 동안 두 번째 시도는 즉시 CONFLICT다.
        ExtractionOutcome second = contentExtractionService.extract(userContext(owner), documentId);
        assertThat(second.kind()).isEqualTo(ExtractionOutcome.Kind.CONFLICT);

        releaseParse.countDown();
        ExtractionOutcome firstResult = first.get(10, TimeUnit.SECONDS);
        assertThat(firstResult.kind()).isEqualTo(ExtractionOutcome.Kind.SUCCESS);
        assertThat(extractedContentJpaRepository.findById(documentId).orElseThrow().isPublished()).isTrue();
    }

    /**
     * M06 후속 교정 - 이전 버전은 {@code SourceConnectionService.disconnect}를
     * 전혀 호출하지 않고 문서 state를 직접 SQL로 바꾼 뒤 텍스트를 수동
     * DELETE했다 - 새로 추가한 {@code deleteExtractedContentForSource} 통합
     * 자체를 실제로 검증하지 못했다. 이 테스트는 실제
     * {@link SourceConnectionService#disconnect}를 호출한다.
     */
    @Test
    void disconnectDuringAnInFlightParseLeavesNoResurrectedContent() throws Exception {
        String owner = "owner-disconnect-inflight-" + unique();
        DocumentFixture fixture = createDocumentWithConnection(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(fixture.documentId(), owner);
        fakeConnector.setContent("content".getBytes());

        CountDownLatch parseStarted = new CountDownLatch(1);
        CountDownLatch releaseParse = new CountDownLatch(1);
        controllableParsingClient.setBehavior(content -> {
            parseStarted.countDown();
            try {
                releaseParse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ParseOutcome.success("plaintext", "1", "1", new String(content),
                    List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, content.length)));
        });

        CompletableFuture<ExtractionOutcome> inFlight = CompletableFuture.supplyAsync(
                () -> contentExtractionService.extract(userContext(owner), fixture.documentId()));
        assertThat(parseStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // 실제 disconnect() 경로 - 이 문서의 in-flight Parsing 도중 일어난다.
        sourceConnectionService.disconnect(fixture.connectionId(), owner);

        releaseParse.countDown();
        ExtractionOutcome result = inFlight.get(10, TimeUnit.SECONDS);

        // M06 후속 교정: finalizePublish의 재검증(문서 ACTIVE 여부)이 Lock을 잡은
        // 상태에서 DELETED를 보고 실패한다 - 이 시도는 여전히 우리가 Claim을
        // 소유한 채 실패한 것이므로 단순 SUPERSEDED가 아니라 FAILED다. 무효화
        // 시도 자체는 Disconnect가 이미 행을 통째로 지웠으므로 0행에 적용되고
        // 조용히 아무 일도 하지 않는다(더 손댈 결과 자체가 없다) - 핵심은
        // 아래처럼 행이 계속 비어 있다는 것이다(되살아나지 않는다).
        assertThat(result.kind()).isEqualTo(ExtractionOutcome.Kind.FAILED);
        assertThat(extractedContentJpaRepository.findById(fixture.documentId())).isEmpty();
        assertThat(sourceDocumentJpaRepository.findById(fixture.documentId()).orElseThrow().getState())
                .isEqualTo("DELETED");
    }

    /**
     * M06 후속 교정. {@code finalizePublish}가 {@code source_documents} 행을
     * {@code SELECT ... FOR UPDATE}로 잠그는 것이, 그 행을 바꾸려는 동시
     * {@code markAllActiveAsDeletedForSource}(Disconnect가 쓰는 것과 동일한
     * Bulk UPDATE)를 실제로 대기시키는지 직접 증명한다 - "발행 직전 읽은
     * 상태"와 "실제 발행이 반영되는 시점" 사이에 다른 Transaction이 끼어들
     * 수 없다는 것의 근거가 되는 기반 메커니즘이다.
     */
    @Test
    void findByIdForUpdateSerializesAgainstAConcurrentBulkStateUpdate() throws Exception {
        String owner = "owner-lock-order-" + unique();
        DocumentFixture fixture = createDocumentWithConnection(owner, "ACTIVE", "ACTIVE", "v1");

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        Thread holder = new Thread(() -> transactionTemplateForTest().executeWithoutResult(status -> {
            sourceDocumentJpaRepository.findByIdForUpdate(fixture.documentId());
            lockHeld.countDown();
            try {
                releaseLock.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Long> blockedForMillis = new AtomicReference<>();
        Thread updater = new Thread(() -> {
            long started = System.nanoTime();
            transactionTemplateForTest().executeWithoutResult(status ->
                    sourceDocumentJpaRepository.markAllActiveAsDeletedForSource(fixture.connectionId()));
            blockedForMillis.set((System.nanoTime() - started) / 1_000_000);
        });
        updater.start();

        // Updater가 Lock 때문에 실제로 대기하고 있는지 확인할 시간을 준다.
        Thread.sleep(300);
        assertThat(updater.isAlive())
                .as("concurrent bulk UPDATE on the locked row must be blocked, not proceed immediately")
                .isTrue();

        releaseLock.countDown();
        holder.join(10_000);
        updater.join(10_000);

        assertThat(blockedForMillis.get()).isNotNull();
        assertThat(blockedForMillis.get()).isGreaterThanOrEqualTo(250L);
        assertThat(sourceDocumentJpaRepository.findById(fixture.documentId()).orElseThrow().getState())
                .isEqualTo("DELETED");
    }

    /**
     * M06 narrow 후속 교정 - 핵심 회귀(Lock 순서 통일). {@code
     * invalidateAndRecordFailure}(실패 확정)와 {@code SourceConnectionService.disconnect}가
     * 서로 반대 순서로 {@code source_documents}/{@code document_extracted_content}를
     * 바꾸면, 각자 상대가 쥔 행을 요구하는 Lock 순환(Postgres Deadlock)이
     * 실제로 발생할 수 있었다(교정 전 순서: 실패 확정은 Content를 먼저,
     * Disconnect는 Document를 먼저). 이 테스트는 그 정확한 교차점(Conflicting
     * Mutation Boundary)을 결정론적으로 재현한다.
     *
     * <p>별도 Holder Thread가 {@code source_documents} 행을 {@code
     * PESSIMISTIC_WRITE}로 먼저 잠그고, 실제 {@code disconnect()}를 그 Lock
     * 대기열에 먼저 세운 뒤(B가 먼저 대기), Parsing이 멈춰 있던 실제 {@code
     * extract()} 실패 Attempt를 재개해 같은 행을 뒤이어 요청하게 만든다(A가
     * 나중에 대기) - 대기 순서를 명시적으로 고정한 채로 Holder를 풀므로,
     * Postgres의 내부 대기열 결정에 결과가 좌우되지 않는다: 교정된 순서에서는
     * 대기열에 먼저 선 B(Disconnect)가 항상 먼저 완료되고, A는 그 시점에
     * 이미 지워진 문서/콘텐츠에 대해 아무 것도 되살리지 않고 조용히 끝난다
     * (되살아난 Content 없음, 거짓 성공 없음, Deadlock 관련 예외 없음).</p>
     *
     * <p>단순히 "FOR UPDATE가 일반 UPDATE를 막는다"는 것만 보이거나(그건
     * {@link #findByIdForUpdateSerializesAgainstAConcurrentBulkStateUpdate}가
     * 이미 증명한다), Parsing이 멈춰 있는 동안 Disconnect를 호출하는 것만으로는
     * (그건 {@link #disconnectDuringAnInFlightParseLeavesNoResurrectedContent}가
     * 이미 증명한다) 이 교차점을 실제로 행사하지 못한다 - 두 Transaction이
     * 각각 자신의 첫 번째 Lock을 이미 쥔 채 두 번째 Lock을 서로에게 요청하는
     * 순간이 실제로 만들어져야 한다.</p>
     */
    @Test
    void failedExtractionAndDisconnectAtTheSharedLockBoundarySettleWithoutDeadlock() throws Exception {
        String owner = "owner-lock-boundary-" + unique();
        DocumentFixture fixture = createDocumentWithConnection(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(fixture.documentId(), owner);
        fakeConnector.setContent("content".getBytes());

        CountDownLatch parseStarted = new CountDownLatch(1);
        CountDownLatch releaseParse = new CountDownLatch(1);
        controllableParsingClient.setBehavior(content -> {
            parseStarted.countDown();
            try {
                releaseParse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ParseOutcome.failure(ParseOutcomeKind.FAILED, "lock boundary test parse error");
        });

        CompletableFuture<ExtractionOutcome> extractFuture = CompletableFuture.supplyAsync(
                () -> contentExtractionService.extract(userContext(owner), fixture.documentId()));
        assertThat(parseStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // Holder(H) - 두 Transaction이 결국 요청할 바로 그 source_documents 행을 먼저 잠근다.
        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactionTemplateForTest().executeWithoutResult(status -> {
            entityManager.find(SourceDocumentEntity.class, fixture.documentId(), LockModeType.PESSIMISTIC_WRITE);
            holderHasLock.countDown();
            try {
                releaseHolder.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(holderHasLock.await(10, TimeUnit.SECONDS)).isTrue();

        // B(실제 disconnect()) - Holder가 아직 안 풀렸으므로 markAllActiveAsDeletedForSource가
        // 곧바로 막힌다. Holder의 대기열에 A보다 먼저 서게 만든다.
        CompletableFuture<Void> disconnectFuture = CompletableFuture.runAsync(
                () -> sourceConnectionService.disconnect(fixture.connectionId(), owner));
        Thread.sleep(300);
        assertThat(disconnectFuture.isDone())
                .as("disconnect must be blocked on the pre-held source_documents lock, not proceeding yet")
                .isFalse();

        // A(실제 실패 확정)를 재개한다 - 같은 행을 요청하며 B 뒤에 대기열에 선다.
        releaseParse.countDown();
        Thread.sleep(300);
        assertThat(extractFuture.isDone())
                .as("the failing extract() attempt must also be waiting on the same pre-held row")
                .isFalse();

        // Holder를 푼다 - 대기열에 먼저 선 B가 먼저 Lock을 받는다.
        releaseHolder.countDown();
        holder.join(10_000);

        // 아래 두 get()이 예외 없이 반환해야 한다 - Deadlock으로 하나가 강제
        // Rollback됐다면 여기서 ExecutionException으로 드러난다(감추지 않는다).
        disconnectFuture.get(15, TimeUnit.SECONDS);
        ExtractionOutcome outcome = extractFuture.get(15, TimeUnit.SECONDS);

        assertThat(outcome.kind()).isEqualTo(ExtractionOutcome.Kind.FAILED);
        assertThat(sourceDocumentJpaRepository.findById(fixture.documentId()).orElseThrow().getState())
                .isEqualTo("DELETED");
        // 되살아난 Content 없음 - Disconnect의 삭제가 최종 상태다.
        assertThat(extractedContentJpaRepository.findById(fixture.documentId())).isEmpty();

        boolean disconnectAudited = auditLogJpaRepository.findAll().stream()
                .anyMatch(log -> "SOURCE_DISCONNECTED".equals(log.getAction())
                        && ("source:" + fixture.connectionId()).equals(log.getTargetId())
                        && "SUCCESS".equals(log.getResult()));
        assertThat(disconnectAudited).isTrue();
        // A는 이 결정론적 순서에서 이미 지워진 문서/Content에 대해 아무 것도 기록하지
        // 않는다(거짓 성공/거짓 실패 정착을 만들지 않는다) - Audit 일관성.
        boolean spuriousFailureAudit = auditLogJpaRepository.findAll().stream()
                .anyMatch(log -> "CONTENT_EXTRACTED".equals(log.getAction())
                        && ("document:" + fixture.documentId()).equals(log.getTargetId()));
        assertThat(spuriousFailureAudit).isFalse();
    }

    /**
     * 이전에 요청됐던 전체 Service 수준 회귀 - "A가 Claim을 잃고, B가 발행하고,
     * 그 다음에야 A가 실패로 끝난다". A의 Parsing은 걸려 있고(Latch), 그 동안
     * A의 Claim을 Stale로 만든 뒤(방치/Crash를 흉내낸다 - {@code
     * attempt_started_at}을 90초 Threshold보다 훨씬 이전으로 되돌린다) B가
     * 정상적으로 재점유해 발행까지 마친다. 그 다음에야 A의 Parsing이 실패로
     * 풀려난다 - 이 시점에 A의 {@code attempt_id}는 이미 B의 발행으로 NULL로
     * 지워져 있으므로(더 이상 A와 일치하지 않으므로), A의 실패 확정은 0행에
     * 적용되고 B의 결과를 전혀 건드리지 않아야 한다.
     */
    @Test
    void lostClaimLateFailureDoesNotAffectTheNewerAttemptsPublishedContent() throws Exception {
        String owner = "owner-lost-claim-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE", "v1");
        grantFreshRead(documentId, owner);
        fakeConnector.setContent("attempt-a".getBytes());

        CountDownLatch aParseStarted = new CountDownLatch(1);
        CountDownLatch releaseAParse = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);
        controllableParsingClient.setBehavior(content -> {
            if (callCount.incrementAndGet() == 1) {
                // A(더 이전 시도) - 나중에야(B가 이미 발행한 뒤에) 실패로 끝난다.
                aParseStarted.countDown();
                try {
                    releaseAParse.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ParseOutcome.failure(ParseOutcomeKind.FAILED, "stale attempt late failure");
            }
            // B(더 새로운 시도) - 정상적으로 성공한다.
            return ParseOutcome.success("plaintext", "1", "1", new String(content),
                    List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, content.length)));
        });

        CompletableFuture<ExtractionOutcome> attemptA = CompletableFuture.supplyAsync(
                () -> contentExtractionService.extract(userContext(owner), documentId));
        assertThat(aParseStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // A의 Claim을 Stale로 만든다(Crash 등으로 방치된 것처럼) - CLAIM_STALE_AFTER_SECONDS(90s)
        // 보다 훨씬 이전으로 attempt_started_at을 되돌린다.
        entityManagerNativeUpdate("UPDATE document_extracted_content SET attempt_started_at = "
                + "attempt_started_at - INTERVAL '200 seconds' WHERE document_id = " + documentId);

        fakeConnector.setContent("attempt-b".getBytes());
        ExtractionOutcome bOutcome = contentExtractionService.extract(userContext(owner), documentId);
        assertThat(bOutcome.kind()).isEqualTo(ExtractionOutcome.Kind.SUCCESS);

        DocumentExtractedContentEntity afterB = extractedContentJpaRepository.findById(documentId).orElseThrow();
        assertThat(afterB.isPublished()).isTrue();
        assertThat(afterB.getNormalizedText()).isEqualTo("attempt-b");
        String contentHashAfterB = afterB.getContentHash();
        SourceDocumentEntity documentAfterB = sourceDocumentJpaRepository.findById(documentId).orElseThrow();
        String indexStatusAfterB = documentAfterB.getIndexStatus();
        String indexReasonAfterB = documentAfterB.getIndexReason();

        // 이제서야 A가 뒤늦게 실패로 끝난다 - 이미 B가 Claim을 재점유하고 발행까지 마쳤다.
        releaseAParse.countDown();
        ExtractionOutcome aOutcome = attemptA.get(10, TimeUnit.SECONDS);
        assertThat(aOutcome.kind()).isEqualTo(ExtractionOutcome.Kind.FAILED);

        // B의 결과는 전혀 바뀌지 않는다 - A의 attempt_id는 더 이상 일치하지 않으므로
        // 무엇도 무효화/기록하지 못한다("Lost Attempt가 새 Attempt의 Content/상태를
        // 절대 바꾸지 않는다").
        DocumentExtractedContentEntity afterA = extractedContentJpaRepository.findById(documentId).orElseThrow();
        assertThat(afterA.isPublished()).isTrue();
        assertThat(afterA.getNormalizedText()).isEqualTo("attempt-b");
        assertThat(afterA.getContentHash()).isEqualTo(contentHashAfterB);
        SourceDocumentEntity documentAfterA = sourceDocumentJpaRepository.findById(documentId).orElseThrow();
        assertThat(documentAfterA.getIndexStatus()).isEqualTo(indexStatusAfterB);
        assertThat(documentAfterA.getIndexReason()).isEqualTo(indexReasonAfterB);
    }

    private TransactionTemplate transactionTemplateForTest() {
        return new TransactionTemplate(transactionManager);
    }

    private long createDocument(String ownerSubject, String connectionStatus, String documentState,
            String sourceVersion) {
        return createDocumentWithConnection(ownerSubject, connectionStatus, documentState, sourceVersion)
                .documentId();
    }

    private DocumentFixture createDocumentWithConnection(String ownerSubject, String connectionStatus,
            String documentState, String sourceVersion) {
        SourceConnectionEntity connection =
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", connectionStatus, "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), "doc-" + unique(), "Doc",
                "text/plain", sourceVersion, null, documentState, "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return new DocumentFixture(document.getId(), connection.getId());
    }

    private record DocumentFixture(Long documentId, Long connectionId) {
    }

    private void grantFreshRead(long documentId, String ownerSubject) {
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(documentId, "user", ownerSubject, "READ", Instant.now()));
    }

    private void setSourceVersion(long documentId, String newVersion) {
        entityManagerNativeUpdate("UPDATE source_documents SET source_version = '" + newVersion
                + "' WHERE id = " + documentId);
    }

    private void entityManagerNativeUpdate(String sql) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(sql).executeUpdate());
    }

    private static UserContext userContext(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of());
    }

    private static String unique() {
        return UUID.randomUUID().toString();
    }

    // -----------------------------------------------------------------
    // Test Double: DocumentSourceConnector
    // -----------------------------------------------------------------
    static class FakeDocumentSourceConnector implements DocumentSourceConnector {
        private final AtomicReference<byte[]> content = new AtomicReference<>(new byte[0]);
        private final AtomicReference<Integer> fetchCount = new AtomicReference<>(0);

        void setContent(byte[] bytes) {
            content.set(bytes);
        }

        int fetchCount() {
            return fetchCount.get();
        }

        void reset() {
            content.set(new byte[0]);
            fetchCount.set(0);
        }

        @Override
        public SourceType supportedType() {
            return SourceType.GOOGLE_DRIVE;
        }

        @Override
        public SourceDocument getMetadata(Long sourceId, String sourceDocumentId) {
            throw new UnsupportedOperationException("not used by ContentExtractionService");
        }

        @Override
        public byte[] fetchContent(Long sourceId, String sourceDocumentId) {
            fetchCount.updateAndGet(c -> c + 1);
            return content.get();
        }

        @Override
        public List<SourcePermission> getPermissions(Long sourceId, String sourceDocumentId) {
            throw new UnsupportedOperationException("not used by ContentExtractionService");
        }

        @Override
        public List<SourceDocument> findChanges(Long sourceId, String syncCursor) {
            throw new UnsupportedOperationException("not used by ContentExtractionService");
        }
    }

    // -----------------------------------------------------------------
    // Test Double: DocumentParsingClient (실제 HTTP 없음)
    // -----------------------------------------------------------------
    static class ControllableDocumentParsingClient extends DocumentParsingClient {
        private volatile java.util.function.Function<byte[], ParseOutcome> behavior =
                content -> ParseOutcome.failure(ParseOutcomeKind.FAILED, "no behavior configured");

        ControllableDocumentParsingClient(RestClient unusedRestClient) {
            super(unusedRestClient);
        }

        void setBehavior(java.util.function.Function<byte[], ParseOutcome> behavior) {
            this.behavior = behavior;
        }

        void reset() {
            this.behavior = content -> ParseOutcome.failure(ParseOutcomeKind.FAILED, "no behavior configured");
        }

        @Override
        public ParseOutcome parse(byte[] content, String fileName, String declaredMimeType) {
            return behavior.apply(content);
        }
    }

    @TestConfiguration
    static class Doubles {
        @Bean
        FakeDocumentSourceConnector fakeDocumentSourceConnector() {
            return new FakeDocumentSourceConnector();
        }

        @Bean
        @Primary
        ControllableDocumentParsingClient controllableDocumentParsingClient(RestClient.Builder builder) {
            return new ControllableDocumentParsingClient(builder.build());
        }
    }
}
