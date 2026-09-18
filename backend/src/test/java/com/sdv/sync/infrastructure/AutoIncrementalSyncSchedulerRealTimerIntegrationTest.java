package com.sdv.sync.infrastructure;

import com.sdv.source.domain.SourceChangePage;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * M17 신규(다중 사용자 환경을 고려한 자동 증분 동기화) - 이 File 하나만 실제 {@code
 * @Scheduled} Wall-Clock Timer(짧은 {@code poll-interval-ms=250})가 사용자의 수동
 * Trigger 없이 스스로 발동해 실제 DB를 갱신하는지 증명한다({@code
 * OutboxEventPublisherIntegrationTest}와 동일한 관례 - 실제 배경 Timer + Awaitility
 * Polling, Sleep 하나로 때우지 않는다). 이 배경 Timer가 다른 결정론적 Test와 섞이면
 * (Mock Stub이 Reset된 뒤에도 계속 실행되어) 예측 불가능한 실패를 만든다는 것을 구현
 * 중 직접 확인했기 때문에, 이 하나의 검증만을 위한 별도 File로 분리했다({@code
 * AutoIncrementalSyncSchedulerIntegrationTest}는 반대로 매우 긴 Poll 주기로 배경
 * Timer를 사실상 비활성화하고 전부 직접 {@code runDueSources()}를 호출한다).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.sync.auto-incremental.enabled=true",
        "sdv.sync.auto-incremental.poll-interval-ms=250",
        "sdv.sync.auto-incremental.batch-size=20",
        "sdv.sync.auto-incremental.max-concurrent=2",
        "sdv.sync.auto-incremental.max-backoff-seconds=60"
})
class AutoIncrementalSyncSchedulerRealTimerIntegrationTest {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository;
    @Autowired
    private SyncRunJpaRepository syncRunJpaRepository;
    @MockitoSpyBean
    private GoogleDriveConnector googleDriveConnector;

    @Test
    void realSchedulerFiresAutomaticallyWithoutAnyManualTriggerAndAdvancesTheCursor() {
        String owner = "owner-auto-sync-real-timer-" + OWNER_SEQUENCE.incrementAndGet();
        Long sourceId = createEligibleSource(owner, "auto-cursor-0");
        doReturn(new SourceChangePage(List.of(), null, "auto-cursor-1", true))
                .when(googleDriveConnector).findChanges(sourceId, "auto-cursor-0");

        // runDueSources()를 직접 부르지 않는다 - 실제 배경 Timer 자체가 발동하는지가 이 Test의 유일한 목적이다.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            SourceSyncCursorEntity reloaded = sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow();
            assertThat(reloaded.getCursor()).isEqualTo("auto-cursor-1");
        });
        verify(googleDriveConnector, times(1)).findChanges(sourceId, "auto-cursor-0");
    }

    private Long createEligibleSource(String owner, String cursorValue) {
        SourceConnectionEntity entity =
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL", owner);
        sourceConnectionJpaRepository.saveAndFlush(entity);
        Long sourceId = entity.getId();
        sourceSyncCursorJpaRepository.saveAndFlush(
                new SourceSyncCursorEntity(sourceId, cursorValue, Instant.now().minusSeconds(600)));
        Instant now = Instant.now();
        SyncRunEntity fullRun = syncRunJpaRepository.saveAndFlush(new SyncRunEntity(sourceId, "FULL", now,
                now.plusSeconds(60)));
        fullRun.applyCounts(1, 1, 0);
        fullRun.finish(SyncRunEntity.STATUS_COMPLETED, now);
        syncRunJpaRepository.saveAndFlush(fullRun);
        return sourceId;
    }
}
