package com.sdv.sync.infrastructure;

import com.sdv.sync.application.IncrementalSyncService;
import com.sdv.sync.application.SyncAlreadyRunningException;
import com.sdv.source.application.port.SourceSyncException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * M17 신규(이번 작업 지시 - 다중 사용자 환경을 고려한 자동 증분 동기화) - 정상적으로
 * 초기 동기화를 마친 활성 Google 연결의 변경분을 기본 30초 주기로 확인한다.
 *
 * <h2>기본 비활성화</h2>
 * <p>{@code sdv.sync.auto-incremental.enabled}가 명시적으로 {@code true}일 때만
 * 이 Bean 자체가 등록된다({@link ConditionalOnProperty} - {@code
 * com.sdv.event.infrastructure.OutboxEventPublisher}와 동일한 관례). 기본
 * OFF일 때는 이 Class 자체가 Spring Context에 존재하지 않으므로 기존 수동 Sync
 * 흐름(테스트베드 포함)은 전혀 바뀌지 않는다.</p>
 *
 * <h2>기존 로직 재사용 - 새 Sync 규칙을 만들지 않는다</h2>
 * <p>이 Class는 {@link IncrementalSyncService#syncChanges(Long, String)}(기존
 * 수동 {@code POST /api/sources/{id}/sync}가 이미 쓰는 바로 그 Public 진입점)을
 * 그대로 호출한다 - Source별 RUNNING 유일성(V008), Lease/Fencing(V009), 페이지
 * 반영·Cursor 전진·Outbox 기록(원자적, {@code SourceSyncPageWriter})은 전혀 다시
 * 만들지 않는다. 성공/부분실패/실패 Audit 기록도 그 기존 경로가 그대로 남긴다 -
 * 이 Class는 "자동으로 트리거했다"는 별도 표시를 추가하지 않는다(요청 범위 밖).</p>
 *
 * <h2>왜 {@code beginAutoRun}이 아니라 {@code syncChanges}인가</h2>
 * <p>{@code SourceSyncService.sync}(→{@code beginAutoRun})는 Cursor 유무로
 * FULL/INCREMENTAL을 고르는 "수동 최초/재개 진입점"이다 - 이 Scheduler가 그것을
 * 그대로 부르면 Cursor 없는 연결에 자동으로 전체 스캔을 반복시킬 위험이 있다(이번
 * 작업 지시가 명시적으로 금지). {@link AutoIncrementalSyncClaimWriter#claimDue}가
 * 고르는 후보는 이미 "Cursor가 있고 최초 FULL Sync가 COMPLETED로 끝난" Source로
 * 한정되므로, 이 Class는 Cursor가 있어야만 호출 가능한 {@link
 * IncrementalSyncService#syncChanges(Long, String)}만 부른다 - 전체 스캔 경로
 * ({@code SourceSyncService.startInitialSync}/{@code GoogleDriveSyncJob.runInitialScan})는
 * 이 Class에서 전혀 참조하지 않는다.</p>
 *
 * <h2>Bounded Worker Pool - 한 Source가 다른 Source를 굶기지 않는다</h2>
 * <p>Claim된 Batch는 고정 크기({@code sdv.sync.auto-incremental.max-concurrent})
 * Thread Pool로 동시 처리한다 - 한 Source의 Google 호출이 오래 걸려도(최악의 경우
 * 기존 {@code sdv.sync.run.max-duration-ms} Lease 전체) 같은 Tick의 다른 Source가
 * 그만큼 굶지 않는다. 실행권 자체의 정확성(동시에 하나만)은 여전히 DB 제약이
 * 보장하므로, 이 Pool은 순전히 처리량/공정성 목적이다.</p>
 *
 * <h2>한 Source의 실패가 전체 Scheduler를 막지 않는다</h2>
 * <p>{@link #processOne}은 각 Source를 독립적으로 try/catch한다 - {@link
 * SyncAlreadyRunningException}(다른 실행이 이미 진행 중, 무해함, Backoff 없음),
 * {@link SourceSyncException}({@code NOT_FOUND} - 연결이 비활성/철회/변경됨,
 * 다음 Claim 조회 자체가 이미 제외하므로 Backoff 불필요; 그 밖({@code
 * ACCESS_UNKNOWN}/{@code FAILED}) - 429/일시 장애/인증 실패를 이 Layer에서
 * 세분화하지 않고 동일하게 상한 있는 지수 Backoff), 그 밖의 예상치 못한 예외
 * (Class 이름만 안전하게 기록, 원본 메시지는 남기지 않는다)를 각각 구분해
 * 처리한다.</p>
 */
@Component
@ConditionalOnProperty(prefix = "sdv.sync.auto-incremental", name = "enabled", havingValue = "true")
public class AutoIncrementalSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoIncrementalSyncScheduler.class);

    private final AutoIncrementalSyncClaimWriter writer;
    private final IncrementalSyncService incrementalSyncService;
    private final AutoIncrementalSyncProperties properties;
    private final ExecutorService executor;

    @Autowired
    public AutoIncrementalSyncScheduler(AutoIncrementalSyncClaimWriter writer,
            IncrementalSyncService incrementalSyncService, AutoIncrementalSyncProperties properties) {
        this.writer = writer;
        this.incrementalSyncService = incrementalSyncService;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(properties.maxConcurrent());
    }

    @Scheduled(fixedDelayString = "${sdv.sync.auto-incremental.poll-interval-ms:30000}")
    public void runDueSources() {
        List<AutoIncrementalSyncClaimWriter.ClaimedSource> claimed =
                writer.claimDue(properties.batchSize(), properties.pollIntervalMs());
        if (claimed.isEmpty()) {
            return;
        }
        List<Future<?>> pending = new ArrayList<>(claimed.size());
        for (AutoIncrementalSyncClaimWriter.ClaimedSource source : claimed) {
            pending.add(executor.submit(() -> processOne(source)));
        }
        awaitAll(pending);
    }

    private void awaitAll(List<Future<?>> pending) {
        for (Future<?> future : pending) {
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException alreadyHandled) {
                // processOne never lets an exception escape - defensive only, nothing more to do.
                log.warn("unexpected auto-incremental sync task failure, class={}",
                        alreadyHandled.getCause() == null ? "unknown" : alreadyHandled.getCause().getClass().getSimpleName());
            }
        }
    }

    /** 패키지 전용 - Test가 개별 Source 처리(SyncAlreadyRunningException/NOT_FOUND/일반 실패 분기)를 직접 통제해 검증하기 위함. */
    void processOne(AutoIncrementalSyncClaimWriter.ClaimedSource source) {
        try {
            incrementalSyncService.syncChanges(source.sourceId(), source.ownerSubject());
            writer.recordSuccess(source.sourceId(), properties.pollIntervalMs());
        } catch (SyncAlreadyRunningException alreadyRunning) {
            // 다른 실행(수동/ACL/다른 Instance)이 이미 이 Source의 실행권을 쥐고 있다 - 무해하다,
            // 이미 Claim 시점에 다음 주기로 밀어 둔 next_check_at을 그대로 둔다(Backoff 아님).
            log.debug("auto-incremental sync skipped, another run already owns source, sourceId={}",
                    source.sourceId());
        } catch (SourceSyncException syncFailure) {
            if (syncFailure.getReason() == SourceSyncException.Reason.NOT_FOUND) {
                // 연결이 비활성/철회/다른 타입으로 바뀌었다 - 다음 Claim 조회가 이미 이런 Source를
                // 걸러내므로 추가 Backoff는 불필요하다(자동으로 다시 대상에서 빠진다).
                return;
            }
            log.warn("auto-incremental sync failed, sourceId={}, reason={}", source.sourceId(),
                    syncFailure.getReason());
            writer.recordFailure(source.sourceId(), properties.pollIntervalMs(), properties.maxBackoffSeconds());
        } catch (RuntimeException unexpected) {
            log.warn("auto-incremental sync failed, sourceId={}, class={}", source.sourceId(),
                    unexpected.getClass().getSimpleName());
            writer.recordFailure(source.sourceId(), properties.pollIntervalMs(), properties.maxBackoffSeconds());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
