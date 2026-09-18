package com.sdv.sync.infrastructure;

import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceSyncCursorEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceSyncCursorJpaRepository;
import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;
import com.sdv.sync.infrastructure.persistence.repository.SyncRunJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M17 신규(다중 사용자 환경을 고려한 자동 증분 동기화) - 실제 Testcontainers PostgreSQL +
 * 고정된 {@link Clock}으로 {@link AutoIncrementalSyncClaimWriter}의 대상 조회/Claim/
 * 성공/실패 Backoff를 결정론적으로 검증한다. Google/Kafka/Ollama/실제 testbed는
 * 호출하지 않는다.
 *
 * <p>{@link ClockTestConfig}가 {@code @Primary} {@link Clock}/{@link
 * AutoIncrementalSyncClaimWriter} Bean을 등록한다 - Writer의 각 Method는 실제
 * {@code @Transactional} Proxy를 통해 호출돼야 하므로({@code
 * com.sdv.sync.application.SourceSyncPageWriter} Class Javadoc의 self-invocation
 * 함정과 같은 이유 - 직접 {@code new}로 만들면 Proxy가 없어 Transaction 자체가
 * 걸리지 않는다), Clock을 고정하면서도 실제 Spring Bean으로 등록해 이 문제를
 * 피한다.</p>
 *
 * <p>여러 Test Method가 같은 DB를 공유하고(Rollback 없음), 각 Assertion은 항상 이
 * Test가 만든 특정 sourceId에만 국한한다({@code SourceSyncServiceIntegrationTest}와
 * 동일한 관례) - 전체 결과 목록에 대한 {@code containsExactly}는 다른 Test가 남긴
 * 행과 우연히 겹칠 수 있어 쓰지 않는다.</p>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, AutoIncrementalSyncClaimWriterTest.ClockTestConfig.class})
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class AutoIncrementalSyncClaimWriterTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        AutoIncrementalSyncClaimWriter testAutoIncrementalSyncClaimWriter(
                SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository,
                SourceConnectionJpaRepository sourceConnectionJpaRepository, Clock fixedClock) {
            return new AutoIncrementalSyncClaimWriter(sourceSyncCursorJpaRepository, sourceConnectionJpaRepository,
                    fixedClock);
        }
    }

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository;
    @Autowired
    private SyncRunJpaRepository syncRunJpaRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private AutoIncrementalSyncClaimWriter writer;
    @Autowired
    private Clock clock;

    @Test
    void dueQueryExcludesSourceWithoutAnyCursorYet() {
        Long sourceId = createSource(owner(), "ACTIVE", "GOOGLE_DRIVE");
        completeInitialFullSync(sourceId);
        // 의도적으로 Cursor를 만들지 않는다 - "최초 동기화 미완료"의 가장 단순한 경우.

        List<Long> due = sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant());

        assertThat(due).as("no source_sync_cursors row yet - never eligible for auto-incremental")
                .doesNotContain(sourceId);
    }

    @Test
    void dueQueryExcludesCursorWhoseOnlyFullRunEndedPartialFailure() {
        // 이번 작업 지시의 명시적 경고(테스트베드 source 2)와 동일한 모양 - Cursor는
        // 있지만 최초 FULL Run이 COMPLETED가 아니라 PARTIAL_FAILURE로 끝난 경우다.
        String owner = owner();
        Long sourceId = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(sourceId, "partial-cursor", clock.instant().minusSeconds(60));
        SyncRunEntity fullRun = syncRunJpaRepository.saveAndFlush(
                new SyncRunEntity(sourceId, "FULL", clock.instant(), clock.instant().plusSeconds(60)));
        fullRun.applyCounts(2, 1, 1);
        fullRun.finish(SyncRunEntity.STATUS_PARTIAL_FAILURE, clock.instant());
        syncRunJpaRepository.saveAndFlush(fullRun);

        List<Long> due = sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant());

        assertThat(due).as("PARTIAL_FAILURE initial sync must not be auto-promoted to eligible")
                .doesNotContain(sourceId);
    }

    @Test
    void dueQueryExcludesWhenNextCheckIsStillInTheFuture() {
        String owner = owner();
        Long sourceId = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(sourceId, "cursor-1", clock.instant());
        completeInitialFullSync(sourceId);
        transactionTemplate.executeWithoutResult(status -> sourceSyncCursorJpaRepository
                .markClaimedForAutoSync(List.of(sourceId), clock.instant().plusSeconds(3600)));

        List<Long> due = sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant());

        assertThat(due).doesNotContain(sourceId);
    }

    @Test
    void dueQueryIncludesAnEligibleAndOverdueSource() {
        String owner = owner();
        Long sourceId = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(sourceId, "cursor-1", clock.instant().minusSeconds(120));
        completeInitialFullSync(sourceId);

        List<Long> due = sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant());

        assertThat(due).contains(sourceId);
    }

    @Test
    void dueQueryExcludesASourceWithACurrentlyRunningRun() {
        String owner = owner();
        Long sourceId = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(sourceId, "cursor-1", clock.instant().minusSeconds(120));
        completeInitialFullSync(sourceId);
        syncRunJpaRepository.saveAndFlush(
                new SyncRunEntity(sourceId, "INCREMENTAL", clock.instant(), clock.instant().plusSeconds(1800)));

        List<Long> due = sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant());

        assertThat(due).as("another RUNNING run already owns this source - do not attempt a second claim")
                .doesNotContain(sourceId);
    }

    @Test
    void dueQueryExcludesDisabledAndNonGoogleSources() {
        String disabledOwner = owner();
        Long disabledSource = createSource(disabledOwner, "DISABLED", "GOOGLE_DRIVE");
        createCursor(disabledSource, "cursor-1", clock.instant().minusSeconds(120));
        completeInitialFullSync(disabledSource);

        String otherTypeOwner = owner();
        Long otherTypeSource = createSource(otherTypeOwner, "ACTIVE", "LOCAL_VAULT");
        createCursor(otherTypeSource, "cursor-1", clock.instant().minusSeconds(120));
        completeInitialFullSync(otherTypeSource);

        List<Long> due = sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant());

        assertThat(due).doesNotContain(disabledSource, otherTypeSource);
    }

    @Test
    void dueQueryOrdersOldestFirstAndRespectsBatchSize() {
        String owner = owner();
        Long older = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(older, "cursor-older", clock.instant().minusSeconds(600));
        completeInitialFullSync(older);
        Long newer = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(newer, "cursor-newer", clock.instant().minusSeconds(120));
        completeInitialFullSync(newer);

        // 다른 Test가 남긴 행과 섞일 수 있으므로(공유 DB, Rollback 없음) 전체 목록에서 이 Test
        // 자신의 두 sourceId의 "상대 순서"만 확인한다 - 절대 위치/개수를 가정하지 않는다.
        List<Long> full = sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant());
        assertThat(full).contains(older, newer);
        assertThat(full.indexOf(older))
                .as("the longer-overdue source must sort before the less-overdue one (fairness)")
                .isLessThan(full.indexOf(newer));

        List<Long> limited = sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(full.size() - 1,
                clock.instant());
        assertThat(limited).as("LIMIT must actually bound the returned batch size").hasSize(full.size() - 1);
    }

    @Test
    void claimAdvancesNextCheckBeforeAnyNetworkWorkWouldHappen() {
        String owner = owner();
        Long sourceId = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(sourceId, "cursor-1", clock.instant().minusSeconds(120));
        completeInitialFullSync(sourceId);

        List<AutoIncrementalSyncClaimWriter.ClaimedSource> claimed = writer.claimDue(1_000_000, 30_000L);

        assertThat(claimed).extracting(AutoIncrementalSyncClaimWriter.ClaimedSource::sourceId).contains(sourceId);
        SourceSyncCursorEntity reloaded = sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow();
        assertThat(reloaded.getNextCheckAt())
                .as("pre-claim must push next_check_at forward immediately, before the caller does any Google call")
                .isEqualTo(clock.instant().plusMillis(30_000L));
        List<Long> dueImmediatelyAfter =
                sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant());
        assertThat(dueImmediatelyAfter).as("a same-tick or another instance's re-claim must not re-select it")
                .doesNotContain(sourceId);
    }

    @Test
    void recordSuccessResetsFailuresAndSchedulesTheNextNormalInterval() {
        String owner = owner();
        Long sourceId = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(sourceId, "cursor-1", clock.instant().minusSeconds(120));
        completeInitialFullSync(sourceId);
        writer.recordFailure(sourceId, 30_000L, 1800L);
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getConsecutiveFailures())
                .isEqualTo(1);

        writer.recordSuccess(sourceId, 30_000L);

        SourceSyncCursorEntity reloaded = sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow();
        assertThat(reloaded.getConsecutiveFailures()).isZero();
        assertThat(reloaded.getNextCheckAt()).isEqualTo(clock.instant().plusMillis(30_000L));
    }

    @Test
    void recordFailureGrowsBackoffExponentiallyAndCapsAtTheConfiguredMaximum() {
        String owner = owner();
        Long sourceId = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(sourceId, "cursor-1", clock.instant().minusSeconds(120));
        completeInitialFullSync(sourceId);
        long pollIntervalMs = 30_000L; // 30초 base
        long maxBackoffSeconds = 100L; // 작게 잡아 Cap 도달을 직접 관찰한다

        writer.recordFailure(sourceId, pollIntervalMs, maxBackoffSeconds); // failures=1 -> 30*2=60s
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getNextCheckAt())
                .isEqualTo(clock.instant().plusSeconds(60));

        writer.recordFailure(sourceId, pollIntervalMs, maxBackoffSeconds); // failures=2 -> 30*4=120s -> capped at 100
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getNextCheckAt())
                .as("backoff must never exceed the configured maximum")
                .isEqualTo(clock.instant().plusSeconds(maxBackoffSeconds));
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getConsecutiveFailures())
                .isEqualTo(2);
    }

    /**
     * 실제 동시성 증명 - 두 실패가 겹쳐도 원자적 증가(DB Column 자신 기준)라서 유실되지
     * 않는다({@code SourceConnectionJpaRepository#incrementConnectionEpoch}와 동일한
     * 근거). Sleep이나 Mock으로 대체하지 않고 실제 두 Thread + 실제 DB Transaction으로
     * 검증한다.
     */
    @Test
    void concurrentRecordFailureFromTwoThreadsNeverLosesAnIncrement() throws Exception {
        String owner = owner();
        Long sourceId = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(sourceId, "cursor-1", clock.instant().minusSeconds(120));
        completeInitialFullSync(sourceId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> writer.recordFailure(sourceId, 30_000L, 1800L));
            Future<?> second = executor.submit(() -> writer.recordFailure(sourceId, 30_000L, 1800L));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getConsecutiveFailures())
                .as("both concurrent failures must be counted - no lost increment")
                .isEqualTo(2);
    }

    /**
     * 실제 동시성 증명 - {@code FOR UPDATE OF sc SKIP LOCKED}가 아직 열려 있는(Commit
     * 전) 다른 Transaction이 이미 잠근 행을 절대 다시 고르지 않는다. 한 Transaction을
     * 실제로 열어 둔 채(Latch로 통제) 다른 Transaction이 동시에 조회하게 만들어 실제 Row
     * Lock 경합을 재현한다 - Sleep이나 Mock Scheduler가 아니라 실제 두 DB Transaction이다.
     * 다른 Test가 남긴 행과 섞일 수 있으므로 이 Test 자신의 sourceId 하나에만 국한해서
     * 확인한다(Batch를 크게 잡아 "현재 due한 모든 행"을 첫 Transaction이 전부 잠그게 한다).
     */
    @Test
    void concurrentClaimAttemptsNeverReSelectARowTheFirstTransactionAlreadyLocked() throws Exception {
        String owner = owner();
        Long sourceId = createSource(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(sourceId, "cursor-1", clock.instant().minusSeconds(120));
        completeInitialFullSync(sourceId);

        CountDownLatch firstTxHoldingLock = new CountDownLatch(1);
        CountDownLatch releaseFirstTx = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> firstClaim = executor.submit(() -> transactionTemplate.execute(status -> {
                List<Long> ids =
                        sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant());
                firstTxHoldingLock.countDown();
                await(releaseFirstTx);
                return ids;
            }));
            assertThat(firstTxHoldingLock.await(10, TimeUnit.SECONDS)).isTrue();

            List<Long> secondClaimWhileLocked = transactionTemplate.execute(
                    status -> sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant()));

            releaseFirstTx.countDown();
            List<Long> firstResult = firstClaim.get(10, TimeUnit.SECONDS);

            assertThat(firstResult).as("the first (batch-1000000) transaction locked every currently-due row")
                    .contains(sourceId);
            assertThat(secondClaimWhileLocked)
                    .as("SKIP LOCKED must never re-select a row the still-open first transaction already locked")
                    .doesNotContain(sourceId);

            List<Long> afterRelease =
                    sourceSyncCursorJpaRepository.findDueForAutoIncrementalSync(1_000_000, clock.instant());
            assertThat(afterRelease).as("once the first transaction releases the lock, the row is claimable again")
                    .contains(sourceId);
        } finally {
            releaseFirstTx.countDown();
            executor.shutdownNow();
        }
    }

    private void completeInitialFullSync(Long sourceId) {
        SyncRunEntity fullRun = syncRunJpaRepository.saveAndFlush(
                new SyncRunEntity(sourceId, "FULL", clock.instant(), clock.instant().plusSeconds(60)));
        fullRun.applyCounts(1, 1, 0);
        fullRun.finish(SyncRunEntity.STATUS_COMPLETED, clock.instant());
        syncRunJpaRepository.saveAndFlush(fullRun);
    }

    private void createCursor(Long sourceId, String cursor, Instant updatedAt) {
        sourceSyncCursorJpaRepository.saveAndFlush(new SourceSyncCursorEntity(sourceId, cursor, updatedAt));
    }

    private Long createSource(String ownerSubject, String status, String type) {
        SourceConnectionEntity entity = new SourceConnectionEntity(type, "Test Source", status, "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(entity);
        return entity.getId();
    }

    private static String owner() {
        return "owner-auto-sync-" + OWNER_SEQUENCE.incrementAndGet();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for controlled concurrency barrier");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for controlled concurrency barrier", interrupted);
        }
    }
}
