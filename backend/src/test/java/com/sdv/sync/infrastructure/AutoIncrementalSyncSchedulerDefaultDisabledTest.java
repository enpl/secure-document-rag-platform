package com.sdv.sync.infrastructure;

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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * M17 신규 - {@code sdv.sync.auto-incremental.enabled}를 전혀 설정하지 않은(모든
 * Profile의 기본값 그대로인) Application Context가 (1) {@link
 * AutoIncrementalSyncScheduler} Bean 자체를 등록하지 않고, (2) 이미 자동 대상 조건을
 * 전부 만족하는 Source가 있어도 {@link GoogleDriveConnector}를 단 한 번도 호출하지
 * 않는다는 것을 증명한다({@code
 * com.sdv.event.infrastructure.OutboxEventPublisherDefaultDisabledTest}와 동일한
 * 관례). Bean 자체가 없으므로 이 Test는 실제 Sleep/Polling 없이도 "OFF 상태에서 자동
 * 호출 0"을 구조적으로 증명한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class AutoIncrementalSyncSchedulerDefaultDisabledTest {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository;
    @Autowired
    private SyncRunJpaRepository syncRunJpaRepository;
    @MockitoSpyBean
    private GoogleDriveConnector googleDriveConnector;

    @Test
    void schedulerBeanIsNotRegisteredAndAnEligibleSourceIsNeverAutoCalled() {
        String owner = "owner-auto-sync-off-" + OWNER_SEQUENCE.incrementAndGet();
        SourceConnectionEntity source =
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL", owner);
        sourceConnectionJpaRepository.saveAndFlush(source);
        Instant now = Instant.now();
        sourceSyncCursorJpaRepository.saveAndFlush(
                new SourceSyncCursorEntity(source.getId(), "cursor-1", now.minusSeconds(3600)));
        SyncRunEntity fullRun =
                syncRunJpaRepository.saveAndFlush(new SyncRunEntity(source.getId(), "FULL", now, now.plusSeconds(60)));
        fullRun.applyCounts(1, 1, 0);
        fullRun.finish(SyncRunEntity.STATUS_COMPLETED, now);
        syncRunJpaRepository.saveAndFlush(fullRun);

        assertThat(applicationContext.getBeanNamesForType(AutoIncrementalSyncScheduler.class))
                .as("sdv.sync.auto-incremental.enabled defaults to false in every profile - the bean must not exist")
                .isEmpty();
        // SourceConnectorRegistry의 기동 시점 supportedType() 조회(Google 호출이 아니다,
        // SourceSyncServiceIntegrationTest Class Javadoc과 동일한 무해한 상시 상호작용)는
        // 허용하고, 실제 동기화성 호출만 0회임을 구체적으로 확인한다.
        verify(googleDriveConnector, never()).findChanges(any(), anyString());
        verify(googleDriveConnector, never()).listMetadata(any(), any());
        verify(googleDriveConnector, never()).getStartPageToken(any());
        verify(googleDriveConnector, never()).getPermissions(any(), anyString());
    }
}
