package com.sdv.sync.infrastructure;

import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.source.domain.SourceChangePage;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceSyncCursorEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceSyncCursorJpaRepository;
import com.sdv.sync.application.IncrementalSyncService;
import com.sdv.sync.application.SyncAlreadyRunningException;
import com.sdv.sync.application.SyncRunLifecycle;
import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;
import com.sdv.sync.infrastructure.persistence.repository.SyncRunJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * M17 신규(다중 사용자 환경을 고려한 자동 증분 동기화) - 실제 Testcontainers PostgreSQL +
 * Mockito로 대체한 {@link GoogleDriveConnector}(실제 Google 호출 0회)로 {@link
 * AutoIncrementalSyncScheduler}의 실제 Spring 배선/Bounded 동시성/개별 Source 실패
 * 격리/기존 Outbox 연결을 검증한다.
 *
 * <p>실제 {@code @Scheduled} Wall-Clock Timer가 스스로 발동하는지(진짜 자동 배선의
 * 증거)는 별도 File({@link AutoIncrementalSyncSchedulerRealTimerIntegrationTest})이
 * 확인한다 - 이 File은 {@code poll-interval-ms}를 의도적으로 아주 크게(사실상
 * 자동으로는 절대 발동하지 않게) 잡고, 모든 Test가 {@link
 * AutoIncrementalSyncScheduler#runDueSources()}를 직접(동기적으로) 한 번 호출해
 * 결정론적으로 검증한다 - 같은 Class 안에 짧은 실제 Poll 주기를 함께 두면 배경에서
 * 계속 도는 실제 Timer가 다른 Test가 만든 데이터를 (Mock Stub이 Reset된 뒤) 실제로
 * 다시 건드려 예측 불가능한 실패를 만든다는 것을 이번 구현 중 직접 확인했다.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.sync.auto-incremental.enabled=true",
        // 의도적으로 매우 크게 - 이 Class의 모든 Test는 runDueSources()를 직접 호출한다.
        // 실제 배경 Timer가 Test 사이에 끼어들지 않게 한다(위 Class Javadoc 참고).
        "sdv.sync.auto-incremental.poll-interval-ms=3600000",
        "sdv.sync.auto-incremental.batch-size=20",
        "sdv.sync.auto-incremental.max-concurrent=2",
        "sdv.sync.auto-incremental.max-backoff-seconds=60"
})
class AutoIncrementalSyncSchedulerIntegrationTest {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();

    @Autowired
    private AutoIncrementalSyncScheduler scheduler;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository;
    @Autowired
    private SyncRunJpaRepository syncRunJpaRepository;
    @Autowired
    private SyncRunLifecycle syncRunLifecycle;
    @Autowired
    private IncrementalSyncService incrementalSyncService;
    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;
    @MockitoSpyBean
    private GoogleDriveConnector googleDriveConnector;

    @Test
    void manualRunDueSourcesNeverReprocessesASourceItAlreadyAdvancedInThePreviousCall() {
        String owner = owner();
        Long sourceId = createEligibleSource(owner, "dup-cursor-0");
        doReturn(new SourceChangePage(List.of(), null, "dup-cursor-1", true))
                .when(googleDriveConnector).findChanges(sourceId, "dup-cursor-0");

        scheduler.runDueSources();
        scheduler.runDueSources(); // 같은 next_check_at 주기 안의 두 번째 Tick 흉내

        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getCursor())
                .isEqualTo("dup-cursor-1");
        verify(googleDriveConnector, times(1)).findChanges(sourceId, "dup-cursor-0");
    }

    @Test
    void ineligibleSourcesAreNeverCalledEvenWhenOtherSourcesAreProcessed() {
        String owner = owner();
        Long noCursor = createSourceConnectionOnly(owner, "ACTIVE", "GOOGLE_DRIVE");
        completeInitialFullSync(noCursor); // Cursor 자체가 없다 - 최초 동기화 미완료
        Long partialFailure = createSourceConnectionOnly(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(partialFailure, "partial-cursor");
        SyncRunEntity partialRun = syncRunJpaRepository.saveAndFlush(new SyncRunEntity(partialFailure, "FULL",
                Instant.now(), Instant.now().plusSeconds(60)));
        partialRun.applyCounts(2, 1, 1);
        partialRun.finish(SyncRunEntity.STATUS_PARTIAL_FAILURE, Instant.now());
        syncRunJpaRepository.saveAndFlush(partialRun);
        Long disabled = createSourceConnectionOnly(owner, "DISABLED", "GOOGLE_DRIVE");
        createCursor(disabled, "disabled-cursor");
        completeInitialFullSync(disabled);
        Long eligible = createEligibleSource(owner, "eligible-cursor-0");
        doReturn(new SourceChangePage(List.of(), null, "eligible-cursor-1", true))
                .when(googleDriveConnector).findChanges(eligible, "eligible-cursor-0");

        scheduler.runDueSources();

        assertThat(sourceSyncCursorJpaRepository.findBySourceId(eligible).orElseThrow().getCursor())
                .isEqualTo("eligible-cursor-1");
        verify(googleDriveConnector, never()).findChanges(eq(noCursor), any());
        verify(googleDriveConnector, never()).findChanges(eq(partialFailure), any());
        verify(googleDriveConnector, never()).findChanges(eq(disabled), any());
    }

    @Test
    void oneSourceFailingWithATransientProviderErrorDoesNotBlockAnotherSourceInTheSameBatch() {
        String owner = owner();
        Long failing = createEligibleSource(owner, "failing-cursor-0");
        doThrow(new SourceSyncException(SourceSyncException.Reason.ACCESS_UNKNOWN,
                "could not obtain a trustworthy answer after bounded retries"))
                .when(googleDriveConnector).findChanges(failing, "failing-cursor-0");
        Long succeeding = createEligibleSource(owner, "succeeding-cursor-0");
        doReturn(new SourceChangePage(List.of(), null, "succeeding-cursor-1", true))
                .when(googleDriveConnector).findChanges(succeeding, "succeeding-cursor-0");

        scheduler.runDueSources();

        assertThat(sourceSyncCursorJpaRepository.findBySourceId(succeeding).orElseThrow().getCursor())
                .as("the other source in the same batch must still make progress")
                .isEqualTo("succeeding-cursor-1");
        SourceSyncCursorEntity failedCursor = sourceSyncCursorJpaRepository.findBySourceId(failing).orElseThrow();
        assertThat(failedCursor.getCursor()).as("no progress was made for the failing source").isEqualTo("failing-cursor-0");
        assertThat(failedCursor.getConsecutiveFailures()).as("a bounded backoff must be recorded").isEqualTo(1);
        // 이 Test Class의 poll-interval-ms=250 기준 base는 최소 1초로 바닥 처리되고(코드 참고),
        // failures=1이면 backoff=1*2=2초다 - "즉시 재시도(0초)"가 아니라는 것만 증명하면 된다.
        assertThat(failedCursor.getNextCheckAt()).as("next check must be pushed out, not retried immediately")
                .isAfter(Instant.now().plusSeconds(1));
    }

    @Test
    void disconnectingASourceAfterClaimResultsInNoBackoffAndNoCrash() {
        String owner = owner();
        Long sourceId = createEligibleSource(owner, "disconnect-cursor-0");
        // Claim 이후에 벌어진 Disconnect를 흉내낸다 - processOne을 직접 통제해 재현한다.
        SourceConnectionEntity source = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        source.changeStatus("DISABLED");
        sourceConnectionJpaRepository.saveAndFlush(source);
        Instant beforeNextCheckAt = sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow()
                .getNextCheckAt();

        scheduler.processOne(new AutoIncrementalSyncClaimWriter.ClaimedSource(sourceId, owner, UUID.randomUUID()));

        verify(googleDriveConnector, never()).findChanges(eq(sourceId), anyString());
        SourceSyncCursorEntity reloaded = sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow();
        assertThat(reloaded.getConsecutiveFailures()).as("NOT_FOUND must not be treated as a backoff-worthy failure")
                .isZero();
        assertThat(reloaded.getNextCheckAt()).as("next_check_at is left as-is - the eligibility filter already excludes it")
                .isEqualTo(beforeNextCheckAt);
    }

    @Test
    void anotherRunAlreadyOwningTheSourceIsSkippedGracefullyWithoutBackoff() {
        String owner = owner();
        Long sourceId = createEligibleSource(owner, "already-running-cursor-0");
        SyncRunEntity alreadyRunning = syncRunLifecycle.beginRun(sourceId, owner, "PERMISSION");
        Instant beforeNextCheckAt = sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow()
                .getNextCheckAt();

        scheduler.processOne(new AutoIncrementalSyncClaimWriter.ClaimedSource(sourceId, owner, UUID.randomUUID()));

        verify(googleDriveConnector, never()).findChanges(eq(sourceId), anyString());
        SourceSyncCursorEntity reloaded = sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow();
        assertThat(reloaded.getConsecutiveFailures()).as("a benign already-running collision is not a failure")
                .isZero();
        assertThat(reloaded.getNextCheckAt()).isEqualTo(beforeNextCheckAt);
        syncRunLifecycle.abortRun(sourceId, alreadyRunning.getId());
    }

    /**
     * 실제 동시성 증명 - {@code max-concurrent=2}로 3개의 대상 Source를 동시에 Claim해도
     * 실제로 2개까지만 동시에 진행되고 세 번째는 그 중 하나가 끝날 때까지 기다린다.
     * Sleep 반복이 아니라 실제 CountDownLatch로 통제한 세 Thread(Bounded Worker Pool의
     * 실제 Thread)의 관측된 최대 동시 실행 수를 직접 확인한다.
     */
    @Test
    void boundedWorkerPoolNeverRunsMoreThanMaxConcurrentSourcesAtOnce() throws Exception {
        String owner = owner();
        Long sourceA = createEligibleSource(owner, "pool-cursor-a-0");
        Long sourceB = createEligibleSource(owner, "pool-cursor-b-0");
        Long sourceC = createEligibleSource(owner, "pool-cursor-c-0");

        AtomicInteger inFlight = new AtomicInteger(0);
        CountDownLatch twoInFlight = new CountDownLatch(2);
        CountDownLatch releaseAll = new CountDownLatch(1);
        doAnswer(invocation -> {
            inFlight.incrementAndGet();
            twoInFlight.countDown();
            assertThat(releaseAll.await(10, TimeUnit.SECONDS)).isTrue();
            inFlight.decrementAndGet();
            return new SourceChangePage(List.of(), null, "advanced", true);
        }).when(googleDriveConnector).findChanges(any(), anyString());

        Thread runner = new Thread(scheduler::runDueSources);
        runner.start();
        try {
            assertThat(twoInFlight.await(10, TimeUnit.SECONDS))
                    .as("max-concurrent=2 must allow two sources to be in flight together").isTrue();
            // 세 번째가 잘못 끼어들지 않는지 짧게 관찰한다(핵심 증거는 위 Latch다 - 이 Sleep은
            // Bound 위반이 실제로 일어날 "기회"를 주기 위한 보조 확인일 뿐이다).
            Thread.sleep(300);
            assertThat(inFlight.get()).as("no more than max-concurrent may be in flight at once").isEqualTo(2);
        } finally {
            releaseAll.countDown();
            runner.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceA).orElseThrow().getCursor())
                .isEqualTo("advanced");
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceB).orElseThrow().getCursor())
                .isEqualTo("advanced");
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceC).orElseThrow().getCursor())
                .isEqualTo("advanced");
    }

    @Test
    void existingOutboxAndPermissionChangeEventsStillFireThroughTheAutoIncrementalPath() {
        String owner = owner();
        Long sourceId = createEligibleSource(owner, "outbox-cursor-0");
        var changedRecord = new com.sdv.source.domain.SourceChangeRecord("ext-file-1",
                com.sdv.source.domain.SourceChangeType.CHANGED,
                new com.sdv.source.domain.SourceDocument(null, sourceId, "ext-file-1", "Doc ext-file-1", "text/plain",
                        "v1", Instant.now(), com.sdv.source.domain.SourceDocumentState.ACTIVE,
                        com.sdv.source.domain.DocumentIndexStatus.PENDING, null));
        doReturn(new SourceChangePage(List.of(changedRecord), null, "outbox-cursor-1", true))
                .when(googleDriveConnector).findChanges(sourceId, "outbox-cursor-0");
        doReturn(com.sdv.source.domain.SourcePermissionsResult.ok(List.of(new com.sdv.source.domain.SourcePermission(
                new com.sdv.source.domain.SourcePrincipal("user", owner), "READ"))))
                .when(googleDriveConnector).getPermissions(eq(sourceId), anyString());

        scheduler.runDueSources();

        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getCursor())
                .isEqualTo("outbox-cursor-1");
        List<OutboxEventEntity> events = outboxEventJpaRepository.findAll().stream()
                .filter(event -> sourceId.toString().equals(event.getPayload().get("sourceId")))
                .toList();
        assertThat(events).as("the existing outbox write path must still fire through the auto-incremental trigger")
                .isNotEmpty();
    }

    /**
     * M17 후속 교정(A1) - 프로세스 중단으로 남은 만료 RUNNING 행이 있어도 자동
     * Scheduler가 그 Source를 영원히 건너뛰지 않는다. 실제로 기존 {@code
     * SyncRunLifecycle.beginRun}의 회수(reapAbandoned) 경로에 도달해 그 만료 행을
     * ABANDONED로 전이시키고 새 Run을 정상적으로 시작·완료한다 - 이 Test는 새 Fencing/
     * 회수 로직을 만들지 않았음을 그대로 보여준다(기존 경로를 그대로 재사용).
     */
    @Test
    void expiredRunningRowIsRecoveredThroughTheSchedulerAndCompletesNormally() {
        String owner = owner();
        Long sourceId = createEligibleSource(owner, "recover-cursor-0");
        Instant now = Instant.now();
        SyncRunEntity staleRun = syncRunJpaRepository.saveAndFlush(
                new SyncRunEntity(sourceId, "INCREMENTAL", now.minusSeconds(3600), now.minusSeconds(60)));
        doReturn(new SourceChangePage(List.of(), null, "recover-cursor-1", true))
                .when(googleDriveConnector).findChanges(sourceId, "recover-cursor-0");

        scheduler.runDueSources();

        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getCursor())
                .as("the stale (expired) RUNNING row must not block auto-recovery")
                .isEqualTo("recover-cursor-1");
        assertThat(syncRunJpaRepository.findById(staleRun.getId()).orElseThrow().getStatus())
                .as("the stale run must be reaped to ABANDONED by the existing beginRun path")
                .isEqualTo(SyncRunEntity.STATUS_ABANDONED);
    }

    /**
     * M17 후속 교정(A1) - 위 회수가 실제로 새 RUNNING 행을 만든 뒤에는, 그 회수된 Run이
     * 여전히(genuinely) 진행 중인 동안 동시에 들어온 두 번째 시도는 기존 V008 부분
     * Unique Index + {@code lockOwnedActiveGoogleSource}의 Row Lock으로 여전히
     * 거부된다 - 이 Scheduler 교정이 기존 상호 배제를 약화시키지 않았음을 실제 두
     * Thread로 증명한다(Sleep이 아니라 Latch로 통제).
     */
    @Test
    void aRecoveredRunIsStillProtectedFromAConcurrentSecondAttemptWhileGenuinelyInFlight() throws Exception {
        String owner = owner();
        Long sourceId = createEligibleSource(owner, "race-cursor-0");
        Instant now = Instant.now();
        syncRunJpaRepository.saveAndFlush(
                new SyncRunEntity(sourceId, "INCREMENTAL", now.minusSeconds(3600), now.minusSeconds(60)));
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inFlight.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new SourceChangePage(List.of(), null, "race-cursor-1", true);
        }).when(googleDriveConnector).findChanges(sourceId, "race-cursor-0");

        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Future<SyncRunEntity> recovery = executor.submit(() -> incrementalSyncService.syncChanges(sourceId, owner));
            assertThat(inFlight.await(10, TimeUnit.SECONDS))
                    .as("the recovered run must be genuinely executing (past reap + new RUNNING row) before the second attempt")
                    .isTrue();

            assertThatThrownBy(() -> incrementalSyncService.syncChanges(sourceId, owner))
                    .as("a second concurrent attempt against the just-recovered (now healthy) run must still be rejected")
                    .isInstanceOf(SyncAlreadyRunningException.class);

            release.countDown();
            SyncRunEntity recovered = recovery.get(10, TimeUnit.SECONDS);
            assertThat(recovered.getStatus()).isEqualTo(SyncRunEntity.STATUS_COMPLETED);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getCursor())
                .isEqualTo("race-cursor-1");
    }

    /**
     * M17 후속 교정(A2) - {@code syncChanges}가 예외 없이 {@code PARTIAL_FAILURE}로
     * 끝나도(이 Test는 신뢰할 수 없는(UNKNOWN) 권한 조회로 재현한다) 더 이상 성공으로
     * 기록되지 않는다. 이전에는 예외만 없으면 무조건 {@code recordSuccess}를 불러 연속
     * 실패 횟수를 0으로 재설정했다(이 Test가 재현하는 실제 결함).
     */
    @Test
    void partialFailureRunStatusTriggersBackoffInsteadOfResettingConsecutiveFailures() {
        String owner = owner();
        Long sourceId = createEligibleSource(owner, "partial-cursor-0");
        var changedRecord = new com.sdv.source.domain.SourceChangeRecord("ext-file-partial",
                com.sdv.source.domain.SourceChangeType.CHANGED,
                new com.sdv.source.domain.SourceDocument(null, sourceId, "ext-file-partial", "Doc", "text/plain",
                        "v1", Instant.now(), com.sdv.source.domain.SourceDocumentState.ACTIVE,
                        com.sdv.source.domain.DocumentIndexStatus.PENDING, null));
        doReturn(new SourceChangePage(List.of(changedRecord), null, "partial-cursor-1", true))
                .when(googleDriveConnector).findChanges(sourceId, "partial-cursor-0");
        doReturn(com.sdv.source.domain.SourcePermissionsResult.unknown())
                .when(googleDriveConnector).getPermissions(eq(sourceId), anyString());

        scheduler.runDueSources();

        SourceSyncCursorEntity reloaded = sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow();
        assertThat(reloaded.getCursor())
                .as("the cursor must not advance past a page containing an untrusted (UNKNOWN) permission")
                .isEqualTo("partial-cursor-0");
        assertThat(reloaded.getConsecutiveFailures())
                .as("a PARTIAL_FAILURE run must trigger backoff, not be recorded as a reset-to-zero success")
                .isEqualTo(1);
    }

    private Long createEligibleSource(String owner, String cursorValue) {
        Long sourceId = createSourceConnectionOnly(owner, "ACTIVE", "GOOGLE_DRIVE");
        createCursor(sourceId, cursorValue);
        completeInitialFullSync(sourceId);
        return sourceId;
    }

    private void createCursor(Long sourceId, String cursorValue) {
        sourceSyncCursorJpaRepository.saveAndFlush(
                new SourceSyncCursorEntity(sourceId, cursorValue, Instant.now().minusSeconds(600)));
    }

    private void completeInitialFullSync(Long sourceId) {
        Instant now = Instant.now();
        SyncRunEntity fullRun = syncRunJpaRepository.saveAndFlush(new SyncRunEntity(sourceId, "FULL", now,
                now.plusSeconds(60)));
        fullRun.applyCounts(1, 1, 0);
        fullRun.finish(SyncRunEntity.STATUS_COMPLETED, now);
        syncRunJpaRepository.saveAndFlush(fullRun);
    }

    private Long createSourceConnectionOnly(String ownerSubject, String status, String type) {
        SourceConnectionEntity entity = new SourceConnectionEntity(type, "Test Source", status, "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(entity);
        return entity.getId();
    }

    private static String owner() {
        return "owner-auto-sync-sched-" + OWNER_SEQUENCE.incrementAndGet();
    }
}
