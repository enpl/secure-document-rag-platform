package com.sdv.sync.application;

import com.sdv.common.exception.NotFoundException;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.audit.infrastructure.persistence.repository.AuditLogJpaRepository;
import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.policy.domain.PolicyDecision;
import com.sdv.policy.domain.PolicyReasonCode;
import com.sdv.policy.infrastructure.persistence.repository.SecurityLabelJpaRepository;
import com.sdv.security.infrastructure.persistence.repository.SecurityFindingJpaRepository;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.source.domain.DocumentIndexStatus;
import com.sdv.source.domain.SourceChangePage;
import com.sdv.source.domain.SourceChangeRecord;
import com.sdv.source.domain.SourceChangeType;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourceDocumentState;
import com.sdv.source.domain.SourceMetadataPage;
import com.sdv.source.domain.SourcePermission;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.domain.SourcePrincipal;
import com.sdv.source.application.SourceSharingService;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceSyncCursorEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceSyncCursorJpaRepository;
import com.sdv.sync.application.job.GoogleDriveSyncJob;
import com.sdv.sync.infrastructure.persistence.entity.SyncRunEntity;
import com.sdv.sync.infrastructure.persistence.repository.SyncRunJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * M09A 초점 검증(Section 7, 카테고리 1/2/3/4/5/6) - 실제 Testcontainers PostgreSQL +
 * Mockito로 대체한 {@link GoogleDriveConnector}(실제 Google 호출 0회, 카테고리 6과
 * 직접 증명)를 사용한다. Kafka는 여기서 함께 검증하지 않는다({@code
 * OutboxEventPublisherIntegrationTest}가 격리된 Test Broker로 별도 검증) - 이 Test는
 * "Outbox 행이 올바르게 쓰였는가"까지만 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.policy.permission-freshness-max-age=PT24H"
})
class SourceSyncServiceIntegrationTest {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();

    @Autowired
    private SourceSyncService sourceSyncService;
    @Autowired
    private IncrementalSyncService incrementalSyncService;
    @Autowired
    private PermissionSyncService permissionSyncService;
    @Autowired
    private EffectivePermissionService effectivePermissionService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    // Spy - 다른 필드는 실제 Bean을 그대로 쓰지만, 이 필드만은 "Page Commit 중간에 실제
    // DB 실패가 나면 Transaction 전체가 Rollback되는가"를 검증하는 Test 하나가
    // 특정 문서의 save() 호출만 실패하도록 stub한다(다른 Test에는 영향 없음 - Spring이
    // Test 사이 Mock 상태를 초기화한다).
    @MockitoSpyBean
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private SourcePermissionJpaRepository sourcePermissionJpaRepository;
    @Autowired
    private SourceSyncCursorJpaRepository sourceSyncCursorJpaRepository;
    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired
    private SyncRunJpaRepository syncRunJpaRepository;
    @Autowired
    private SyncRunLifecycle syncRunLifecycle;
    @Autowired
    private GoogleDriveSyncJob googleDriveSyncJob;
    @Autowired
    private SourceSyncPageWriter sourceSyncPageWriter;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private AuditLogJpaRepository auditLogJpaRepository;
    @Autowired
    private SecurityLabelJpaRepository securityLabelJpaRepository;
    @Autowired
    private SecurityFindingJpaRepository securityFindingJpaRepository;
    @Autowired
    private SourceSharingService sourceSharingService;
    // Spy(Mock 아님) - 실제 Spring Bean(실제 의존성 전부 정상 연결됨) 위에 얹는다.
    // SourceConnectorRegistry가 기동 시점에 읽는 supportedType()은 그대로 실제 메서드가
    // 응답한다(고정 상수 반환, 필드 의존 없음) - Mock이었다면 stub 전까지 null을 반환해
    // Registry 생성 자체가 NPE로 실패했을 것이다. 나머지 각 메서드는 Test마다
    // doReturn(...).when(spy)....로 stub한다(spy는 when(spy.x()).thenReturn(...) 형태를 쓰면
    // stub 전에 실제 메서드가 먼저 실행돼 버리므로 - 실제 Google/DB 호출을 유발할 수 있어
    // 피한다).
    @MockitoSpyBean
    private GoogleDriveConnector googleDriveConnector;

    @Test
    void initialScanAppliesAllPagesAndCommitsTheCapturedStartCursorOnlyWhenFullyComplete() {
        String owner = owner();
        Long sourceId = createSource(owner);
        doReturn("start-1").when(googleDriveConnector).getStartPageToken(sourceId);
        SourceDocument docA = document(sourceId, "file-a", "v1");
        SourceDocument docB = document(sourceId, "file-b", "v1");
        SourceDocument docC = document(sourceId, "file-c", "v1");
        doReturn(new SourceMetadataPage(List.of(docA, docB), "p2", false, true))
                .when(googleDriveConnector).listMetadata(sourceId, null);
        doReturn(new SourceMetadataPage(List.of(docC), null, true, true))
                .when(googleDriveConnector).listMetadata(sourceId, "p2");
        doReturn(okPermissions("alice@example.com")).when(googleDriveConnector)
                .getPermissions(eq(sourceId), anyString());
        // M09A 교정 - Metadata 훑기가 끝나면 같은 Run 안에서 startCursor부터 Catch-up이
        // 이어진다. 이 Test는 "그동안 아무것도 바뀌지 않았다"는 가장 단순한 경우를 다룬다.
        doReturn(new SourceChangePage(List.of(), null, "start-1-caught-up", true))
                .when(googleDriveConnector).findChanges(sourceId, "start-1");

        SyncRunEntity run = sourceSyncService.sync(sourceId, owner);

        assertThat(run.getStatus()).isEqualTo(SyncRunEntity.STATUS_COMPLETED);
        assertThat(run.getTotal()).isEqualTo(3);
        assertThat(run.getSuccess()).isEqualTo(3);
        assertThat(run.getFailed()).isZero();
        assertThat(sourceDocumentJpaRepository.findBySourceIdAndStateNot(sourceId, "DELETED")).hasSize(3);
        SourceSyncCursorEntity cursor = sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow();
        assertThat(cursor.getCursor())
                .as("the final cursor is the one Catch-up drained to, not the raw pre-scan startCursor")
                .isEqualTo("start-1-caught-up");
        List<OutboxEventEntity> events = eventsForSource(sourceId);
        assertThat(events).hasSize(6); // 3 x SOURCE_DOCUMENT_CHANGED + 3 x SOURCE_PERMISSION_CHANGED
        assertThat(events).allSatisfy(event -> assertThat(event.getStatus()).isEqualTo(OutboxEventEntity.STATUS_PENDING));
        assertThat(events).extracting(OutboxEventEntity::getEventType)
                .containsOnly("SOURCE_DOCUMENT_CHANGED", "SOURCE_PERMISSION_CHANGED");
    }

    @Test
    void initialScanCatchUpRetiresAFileRemovedWhileListingBeforeReportingCompletion() {
        String owner = owner();
        Long sourceId = createSource(owner);
        doReturn("start-removal").when(googleDriveConnector).getStartPageToken(sourceId);
        SourceDocument removedDuringListing = document(sourceId, "file-removed-during-listing", "v1");
        SourceDocument stillPresent = document(sourceId, "file-still-present", "v1");
        doReturn(new SourceMetadataPage(List.of(removedDuringListing), "list-page-2", false, true))
                .when(googleDriveConnector).listMetadata(sourceId, null);
        doReturn(new SourceMetadataPage(List.of(stillPresent), null, true, true))
                .when(googleDriveConnector).listMetadata(sourceId, "list-page-2");
        doReturn(okPermissions(owner)).when(googleDriveConnector).getPermissions(eq(sourceId), anyString());
        doReturn(new SourceChangePage(List.of(new SourceChangeRecord("file-removed-during-listing",
                SourceChangeType.REMOVED_OR_ACCESS_LOST, null)), null, "cursor-after-removal", true))
                .when(googleDriveConnector).findChanges(sourceId, "start-removal");

        SyncRunEntity run = sourceSyncService.sync(sourceId, owner);

        assertThat(run.getStatus()).isEqualTo(SyncRunEntity.STATUS_COMPLETED);
        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId,
                "file-removed-during-listing").orElseThrow().getState()).isEqualTo("DELETED");
        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, "file-still-present"))
                .isPresent();
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getCursor())
                .isEqualTo("cursor-after-removal");
    }

    @Test
    void failedInitialCatchUpKeepsTheLastSuccessfullyAppliedChangeCursor() {
        String owner = owner();
        Long sourceId = createSource(owner);
        doReturn("start-catch-up").when(googleDriveConnector).getStartPageToken(sourceId);
        SourceDocument listed = document(sourceId, "file-changing-during-listing", "v1");
        doReturn(new SourceMetadataPage(List.of(listed), null, true, true))
                .when(googleDriveConnector).listMetadata(sourceId, null);
        doReturn(okPermissions(owner), SourcePermissionsResult.unknown()).when(googleDriveConnector)
                .getPermissions(sourceId, "file-changing-during-listing");
        doReturn(new SourceChangePage(List.of(), "catch-up-page-2", null, false))
                .when(googleDriveConnector).findChanges(sourceId, "start-catch-up");
        SourceDocument changed = document(sourceId, "file-changing-during-listing", "v2");
        doReturn(new SourceChangePage(List.of(new SourceChangeRecord("file-changing-during-listing",
                SourceChangeType.CHANGED, changed)), null, "cursor-after-failed-page", true))
                .when(googleDriveConnector).findChanges(sourceId, "catch-up-page-2");

        SyncRunEntity run = sourceSyncService.sync(sourceId, owner);

        assertThat(run.getStatus()).isEqualTo(SyncRunEntity.STATUS_PARTIAL_FAILURE);
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getCursor())
                .as("the failed ACL page must retry from the last successful catch-up page")
                .isEqualTo("catch-up-page-2");
        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId,
                "file-changing-during-listing").orElseThrow().getPermissionsUntrustedSince()).isNotNull();
    }

    @Test
    void initialScanWithAnIncompletePageNeverCommitsTheCursorOrClaimsCompletion() {
        String owner = owner();
        Long sourceId = createSource(owner);
        doReturn("start-1").when(googleDriveConnector).getStartPageToken(sourceId);
        SourceDocument doc = document(sourceId, "file-a", "v1");
        doReturn(new SourceMetadataPage(List.of(doc), null, true, false)) // isComplete=false
                .when(googleDriveConnector).listMetadata(sourceId, null);
        doReturn(okPermissions("alice")).when(googleDriveConnector).getPermissions(eq(sourceId), anyString());

        SyncRunEntity run = sourceSyncService.sync(sourceId, owner);

        assertThat(run.getStatus()).isEqualTo(SyncRunEntity.STATUS_PARTIAL_FAILURE);
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId))
                .as("an incomplete traversal must never commit a cursor that later authorizes deletion-by-absence")
                .isEmpty();
        // 이미 반영된 문서 자체는 유지된다 - 재실행이 낭비일 뿐 안전하다.
        assertThat(sourceDocumentJpaRepository.findBySourceIdAndStateNot(sourceId, "DELETED")).hasSize(1);
        assertThat(auditLogJpaRepository.findAll().stream()
                .filter(audit -> owner.equals(audit.getActor())
                        && "SOURCE_SYNC_PARTIAL_FAILURE".equals(audit.getAction()))
                .toList()).singleElement().satisfies(audit -> {
                    assertThat(audit.getResult()).isEqualTo("PARTIAL_FAILURE");
                    assertThat(audit.getReasonCode()).isEqualTo("SYNC_INCOMPLETE");
                });
    }

    @Test
    void incrementalSyncAdvancesCursorPerPageAndRetiresRemovedOrAccessLostDocuments() {
        String owner = owner();
        Long sourceId = createSource(owner);
        sourceSyncCursorJpaRepository.saveAndFlush(new SourceSyncCursorEntity(sourceId, "cursor-0", Instant.now()));
        SourceDocumentEntity existing = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(sourceId, "file-old", "Old.txt", "text/plain", "v1", null, "ACTIVE",
                        "INDEXED", null));
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(existing.getId(), "user", "bob@example.com", "READ", Instant.now()));

        SourceDocument newDoc = document(sourceId, "file-new", "v1");
        doReturn(new SourceChangePage(
                List.of(new SourceChangeRecord("file-new", SourceChangeType.CHANGED, newDoc)), "c2", null, false))
                .when(googleDriveConnector).findChanges(sourceId, "cursor-0");
        doReturn(new SourceChangePage(
                List.of(new SourceChangeRecord("file-old", SourceChangeType.REMOVED_OR_ACCESS_LOST, null)), null,
                "cursor-2", true)).when(googleDriveConnector).findChanges(sourceId, "c2");
        doReturn(okPermissions("carol")).when(googleDriveConnector).getPermissions(eq(sourceId), eq("file-new"));

        SyncRunEntity run = sourceSyncService.sync(sourceId, owner);

        assertThat(run.getStatus()).isEqualTo(SyncRunEntity.STATUS_COMPLETED);
        assertThat(run.getTotal()).isEqualTo(2); // 1 changed + 1 removed
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getCursor())
                .isEqualTo("cursor-2");
        SourceDocumentEntity retired = sourceDocumentJpaRepository.findById(existing.getId()).orElseThrow();
        assertThat(retired.getState()).isEqualTo("DELETED");
        assertThat(sourcePermissionJpaRepository.findByDocumentId(existing.getId())).isEmpty();
        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, "file-new")).isPresent();
    }

    @Test
    void concurrentSyncTriggersForTheSameSourceAreRejectedAtTheDatabaseLevel() {
        String owner = owner();
        Long sourceId = createSource(owner);
        sourceSyncService.beginRun(sourceId, owner, "FULL"); // leaves a RUNNING row uncommitted-to-finish

        assertThatThrownBy(() -> sourceSyncService.sync(sourceId, owner))
                .as("V008's partial unique index must reject a second concurrent RUNNING row - DB level, not JVM mutex")
                .isInstanceOf(SyncAlreadyRunningException.class);
    }

    @Test
    void unknownOrFailedAclNeverOverwritesExistingPermissionsButMetadataStillUpdates() {
        String owner = owner();
        Long sourceId = createSource(owner);
        sourceSyncCursorJpaRepository.saveAndFlush(new SourceSyncCursorEntity(sourceId, "cursor-0", Instant.now()));
        SourceDocumentEntity existing = sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(sourceId, "file-x", "X.txt", "text/plain", "v1", null, "ACTIVE", "INDEXED",
                        null));
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(existing.getId(), "user", "alice@example.com", "READ", Instant.now()));

        SourceDocument changedDoc = document(sourceId, "file-x", "v2"); // 실제 Version 변경
        doReturn(new SourceChangePage(
                List.of(new SourceChangeRecord("file-x", SourceChangeType.CHANGED, changedDoc)), null, "cursor-1",
                true)).when(googleDriveConnector).findChanges(sourceId, "cursor-0");
        doReturn(SourcePermissionsResult.unknown()).when(googleDriveConnector).getPermissions(sourceId, "file-x");

        SyncRunEntity run = incrementalSyncService.syncChanges(sourceId, owner);

        assertThat(run.getStatus()).isEqualTo(SyncRunEntity.STATUS_PARTIAL_FAILURE);
        assertThat(run.getFailed()).isEqualTo(1);
        assertThat(sourcePermissionJpaRepository.findByDocumentId(existing.getId()))
                .as("an UNKNOWN permission read must never delete or silently refresh the existing (stale) ACL row")
                .extracting(SourcePermissionEntity::getPrincipalValue)
                .containsExactly("alice@example.com");
        SourceDocumentEntity reloaded = sourceDocumentJpaRepository.findById(existing.getId()).orElseThrow();
        assertThat(reloaded.getSourceVersion()).as("metadata itself is independent of ACL freshness").isEqualTo("v2");
        assertThat(reloaded.getPermissionsUntrustedSince())
                .as("M09A correction: a failed ACL refresh must mark the evidence explicitly untrusted")
                .isNotNull();
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getCursor())
                .as("M09A correction: a page with an ACL failure must not commit its cursor - stays retryable")
                .isEqualTo("cursor-0");
        List<OutboxEventEntity> events = eventsForSource(sourceId);
        assertThat(events).extracting(OutboxEventEntity::getEventType).containsExactly("SOURCE_DOCUMENT_CHANGED");
    }

    @Test
    void anOlderPermissionGrantCannotRestoreAuthorityAfterANewerCatalogDenial() throws Exception {
        String owner = owner();
        Long sourceId = createSource(owner);
        sourceSyncCursorJpaRepository.saveAndFlush(new SourceSyncCursorEntity(sourceId, "race-cursor-0", Instant.now()));
        SourceDocumentEntity existing = sourceDocumentJpaRepository.saveAndFlush(new SourceDocumentEntity(sourceId,
                "race-file", "Race.txt", "text/plain", "v1", null, "ACTIVE", "INDEXED", null));
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(existing.getId(), "user", owner, "READ", Instant.now()));

        CountDownLatch oldFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseOldFetch = new CountDownLatch(1);
        AtomicInteger permissionFetches = new AtomicInteger();
        doAnswer(invocation -> {
            if (permissionFetches.incrementAndGet() == 1) {
                oldFetchStarted.countDown();
                await(releaseOldFetch);
                return okPermissions(owner);
            }
            return SourcePermissionsResult.ok(List.of());
        }).when(googleDriveConnector).getPermissions(sourceId, "race-file");
        SourceDocument newer = document(sourceId, "race-file", "v2");
        doReturn(new SourceChangePage(List.of(new SourceChangeRecord("race-file", SourceChangeType.CHANGED, newer)),
                null, "race-cursor-1", true)).when(googleDriveConnector).findChanges(sourceId, "race-cursor-0");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<PermissionSyncService.PermissionSyncResult> older =
                executor.submit(() -> permissionSyncService.syncPermissions(sourceId, owner));
        try {
            assertThat(oldFetchStarted.await(10, TimeUnit.SECONDS)).isTrue();
            expireRunningRun(sourceId);

            SyncRunEntity newerRun = sourceSyncService.sync(sourceId, owner);
            assertThat(newerRun.getStatus()).isEqualTo(SyncRunEntity.STATUS_COMPLETED);
            assertDenied(owner, existing.getId());

            releaseOldFetch.countDown();
            assertThat(older.get(10, TimeUnit.SECONDS).failed()).isEqualTo(1);
            assertDenied(owner, existing.getId());
            assertThat(sourcePermissionJpaRepository.findByDocumentId(existing.getId())).isEmpty();
        } finally {
            releaseOldFetch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void anOlderPermissionFailureCannotDistrustANewerSuccessfulCatalogObservation() throws Exception {
        String owner = owner();
        Long sourceId = createSource(owner);
        sourceSyncCursorJpaRepository.saveAndFlush(new SourceSyncCursorEntity(sourceId, "success-cursor-0", Instant.now()));
        SourceDocumentEntity existing = sourceDocumentJpaRepository.saveAndFlush(new SourceDocumentEntity(sourceId,
                "success-file", "Success.txt", "text/plain", "v1", null, "ACTIVE", "INDEXED", null));
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(existing.getId(), "user", owner, "READ", Instant.now()));

        CountDownLatch oldFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseOldFetch = new CountDownLatch(1);
        AtomicInteger permissionFetches = new AtomicInteger();
        doAnswer(invocation -> {
            if (permissionFetches.incrementAndGet() == 1) {
                oldFetchStarted.countDown();
                await(releaseOldFetch);
                return SourcePermissionsResult.unknown();
            }
            return okPermissions(owner);
        }).when(googleDriveConnector).getPermissions(sourceId, "success-file");
        SourceDocument newer = document(sourceId, "success-file", "v2");
        doReturn(new SourceChangePage(List.of(new SourceChangeRecord("success-file", SourceChangeType.CHANGED, newer)),
                null, "success-cursor-1", true)).when(googleDriveConnector).findChanges(sourceId, "success-cursor-0");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<PermissionSyncService.PermissionSyncResult> older =
                executor.submit(() -> permissionSyncService.syncPermissions(sourceId, owner));
        try {
            assertThat(oldFetchStarted.await(10, TimeUnit.SECONDS)).isTrue();
            expireRunningRun(sourceId);

            SyncRunEntity newerRun = sourceSyncService.sync(sourceId, owner);
            assertThat(newerRun.getStatus()).isEqualTo(SyncRunEntity.STATUS_COMPLETED);
            assertAllowed(owner, existing.getId());

            releaseOldFetch.countDown();
            assertThat(older.get(10, TimeUnit.SECONDS).failed()).isEqualTo(1);
            assertAllowed(owner, existing.getId());
            assertThat(sourceDocumentJpaRepository.findById(existing.getId()).orElseThrow()
                    .getPermissionsUntrustedSince()).isNull();
        } finally {
            releaseOldFetch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void unchangedAclReplayRefreshesRowsWithoutEmittingAnotherSemanticPermissionEvent() {
        String owner = owner();
        Long sourceId = createSource(owner);
        sourceSyncCursorJpaRepository.saveAndFlush(new SourceSyncCursorEntity(sourceId, "same-acl-0", Instant.now()));
        SourceDocumentEntity existing = sourceDocumentJpaRepository.saveAndFlush(new SourceDocumentEntity(sourceId,
                "same-acl-file", "Same.txt", "text/plain", "v1", null, "ACTIVE", "INDEXED", null));
        SourcePermissionEntity original = sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(existing.getId(), "user", owner, "READ", Instant.now()));
        SourceDocument unchanged = document(sourceId, "same-acl-file", "v1");
        doReturn(new SourceChangePage(List.of(new SourceChangeRecord("same-acl-file", SourceChangeType.CHANGED,
                unchanged)), null, "same-acl-1", true)).when(googleDriveConnector).findChanges(sourceId, "same-acl-0");
        doReturn(okPermissions(owner)).when(googleDriveConnector).getPermissions(sourceId, "same-acl-file");

        SyncRunEntity run = sourceSyncService.sync(sourceId, owner);

        assertThat(run.getStatus()).isEqualTo(SyncRunEntity.STATUS_COMPLETED);
        assertThat(sourcePermissionJpaRepository.findByDocumentId(existing.getId())).singleElement()
                .extracting(SourcePermissionEntity::getId).isNotEqualTo(original.getId());
        assertThat(eventsForSource(sourceId)).isEmpty();
    }

    @Test
    void unauthenticatedOrForeignOwnerOrInactiveSourceIsRejectedWithoutAnyExternalCall() {
        String owner = owner();
        Long sourceId = createSource(owner);

        assertThatThrownBy(() -> sourceSyncService.sync(sourceId, "someone-else"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sourceSyncService.sync(999_999_999L, owner)).isInstanceOf(NotFoundException.class);

        SourceConnectionEntity disabled = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        disabled.changeStatus("DISABLED");
        sourceConnectionJpaRepository.saveAndFlush(disabled);
        assertThatThrownBy(() -> sourceSyncService.sync(sourceId, owner)).isInstanceOf(SourceSyncException.class);

        verifyNoInteractions(googleDriveConnector);
    }

    @Test
    void catalogSyncNeverCallsFetchContent() {
        String owner = owner();
        Long sourceId = createSource(owner);
        doReturn("start-1").when(googleDriveConnector).getStartPageToken(sourceId);
        doReturn(new SourceMetadataPage(List.of(document(sourceId, "file-a", "v1")), null, true, true))
                .when(googleDriveConnector).listMetadata(sourceId, null);
        doReturn(okPermissions("alice")).when(googleDriveConnector).getPermissions(eq(sourceId), anyString());
        doReturn(new SourceChangePage(List.of(), null, "start-1-caught-up", true))
                .when(googleDriveConnector).findChanges(sourceId, "start-1");

        sourceSyncService.sync(sourceId, owner);

        verify(googleDriveConnector, never()).fetchContent(any(UserContext.class), any(), anyString(), any());
    }

    @Test
    void aSourceDisabledMidScanIsNeverUndoneByTheRemainingPages() {
        String owner = owner();
        Long sourceId = createSource(owner);
        doReturn("start-1").when(googleDriveConnector).getStartPageToken(sourceId);
        SourceDocument docA = document(sourceId, "file-a", "v1");
        SourceDocument docB = document(sourceId, "file-b", "v1");
        doReturn(new SourceMetadataPage(List.of(docA), "p2", false, true))
                .when(googleDriveConnector).listMetadata(sourceId, null);
        doAnswer(invocation -> {
            // 두 번째 Page를 가져오는 시점에 동시 Disconnect가 끼어든 것을 흉내낸다.
            SourceConnectionEntity source = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
            source.changeStatus("DISABLED");
            sourceConnectionJpaRepository.saveAndFlush(source);
            return new SourceMetadataPage(List.of(docB), null, true, true);
        }).when(googleDriveConnector).listMetadata(sourceId, "p2");
        doReturn(okPermissions("alice")).when(googleDriveConnector).getPermissions(eq(sourceId), anyString());

        SyncRunEntity run = sourceSyncService.startInitialSync(sourceId, owner);

        assertThat(run.getStatus()).isEqualTo(SyncRunEntity.STATUS_PARTIAL_FAILURE);
        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, "file-a"))
                .as("page 1 (applied before the concurrent disconnect) is kept")
                .isPresent();
        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, "file-b"))
                .as("a late sync write for page 2 must never resurrect data after a concurrent disconnect")
                .isEmpty();
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId)).isEmpty();
    }

    /**
     * M09A 교정 핵심 시나리오 - 방치된(Lease 만료) RUNNING 행이 있어도, 다음 인가된
     * 요청이 안전하게 회수(ABANDONED)하고 정상적으로 새 Run을 시작한다. 회수된 뒤에는
     * 원래(만료된) Worker의 뒤늦은 완료/실패 보고가 아무것도 되돌리지 못한다(Fencing).
     */
    @Test
    void anAbandonedRunIsRecoveredByALaterRequestAndTheExpiredWorkerCannotMutateAnythingAfterwards() {
        String owner = owner();
        Long sourceId = createSource(owner);
        sourceSyncCursorJpaRepository.saveAndFlush(new SourceSyncCursorEntity(sourceId, "cursor-0", Instant.now()));
        // Lease가 이미 지난 RUNNING 행을 직접 심는다 - 방치된 Worker를 흉내낸다.
        SyncRunEntity abandoned = syncRunJpaRepository.saveAndFlush(
                new SyncRunEntity(sourceId, "INCREMENTAL", Instant.now().minusSeconds(7200),
                        Instant.now().minusSeconds(3600)));
        Long abandonedRunId = abandoned.getId();

        doReturn(new SourceChangePage(List.of(), null, "cursor-1", true))
                .when(googleDriveConnector).findChanges(sourceId, "cursor-0");

        SyncRunEntity newRun = incrementalSyncService.syncChanges(sourceId, owner);

        assertThat(newRun.getId()).isNotEqualTo(abandonedRunId);
        assertThat(newRun.getStatus()).isEqualTo(SyncRunEntity.STATUS_COMPLETED);
        SyncRunEntity reloadedAbandoned = syncRunJpaRepository.findById(abandonedRunId).orElseThrow();
        assertThat(reloadedAbandoned.getStatus())
                .as("the stale RUNNING row must be reaped to ABANDONED before a new run can start")
                .isEqualTo(SyncRunEntity.STATUS_ABANDONED);

        // 만료된 Worker가 뒤늦게 자신의(이미 회수된) runId로 완료/실패를 보고해도 아무것도 바뀌지 않는다.
        SyncRunEntity afterLateFinish = syncRunLifecycle.finishRun(abandonedRunId,
                new GoogleDriveSyncJob.SyncPageLoopResult(true, true, true, 5, 0, 0), owner, sourceId, "INCREMENTAL");
        assertThat(afterLateFinish.getStatus())
                .as("a late finishRun from the expired worker must not overwrite the ABANDONED state")
                .isEqualTo(SyncRunEntity.STATUS_ABANDONED);
        syncRunLifecycle.abortRun(sourceId, abandonedRunId);
        assertThat(syncRunJpaRepository.findById(abandonedRunId).orElseThrow().getStatus())
                .as("a late abortRun from the expired worker must not overwrite the ABANDONED state either")
                .isEqualTo(SyncRunEntity.STATUS_ABANDONED);
        // 새 Run 자체는 멀쩡히 COMPLETED로 남아있다 - 옛 Worker의 뒤늦은 호출과 무관하다.
        assertThat(syncRunJpaRepository.findById(newRun.getId()).orElseThrow().getStatus())
                .isEqualTo(SyncRunEntity.STATUS_COMPLETED);
    }

    @Test
    void anExpiredButStillRunningRunCannotCommitAPageOrReportCompletion() {
        String owner = owner();
        Long sourceId = createSource(owner);
        SyncRunEntity expired = syncRunJpaRepository.saveAndFlush(new SyncRunEntity(sourceId, "INCREMENTAL",
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(1)));
        SourceDocument late = document(sourceId, "late-file", "v1");

        SourceSyncPageWriter.PageApplyResult pageResult = sourceSyncPageWriter.applyPage(sourceId, expired.getId(),
                List.of(new SourceSyncPageWriter.PreparedChange(
                        new SourceChangeRecord("late-file", SourceChangeType.CHANGED, late), okPermissions(owner))),
                "late-cursor");

        assertThat(pageResult.runOwned()).isFalse();
        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, "late-file")).isEmpty();
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId)).isEmpty();
        SyncRunEntity afterLateFinish = syncRunLifecycle.finishRun(expired.getId(),
                new GoogleDriveSyncJob.SyncPageLoopResult(true, true, true, 1, 0, 0), owner, sourceId,
                "INCREMENTAL");
        assertThat(afterLateFinish.getStatus()).isEqualTo(SyncRunEntity.STATUS_ABANDONED);
    }

    @Test
    void aRunForAnotherSourceCannotCommitEvenWhenItsLeaseIsHealthy() {
        String owner = owner();
        Long sourceA = createSource(owner);
        Long sourceB = createSource(owner);
        SyncRunEntity runA = syncRunLifecycle.beginRun(sourceA, owner, "INCREMENTAL");
        SourceDocument wrongSourcePage = document(sourceB, "wrong-source-file", "v1");

        SourceSyncPageWriter.PageApplyResult result = sourceSyncPageWriter.applyPage(sourceB, runA.getId(),
                List.of(new SourceSyncPageWriter.PreparedChange(
                        new SourceChangeRecord("wrong-source-file", SourceChangeType.CHANGED, wrongSourcePage),
                        okPermissions(owner))),
                "wrong-source-cursor");

        assertThat(result.runOwned()).isFalse();
        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceB, "wrong-source-file")).isEmpty();
        syncRunLifecycle.abortRun(sourceA, runA.getId());
    }

    @Test
    void delayedAutoSyncRechecksTheCursorAfterAcquiringSourceOwnership() throws Exception {
        String owner = owner();
        Long sourceId = createSource(owner);
        CountDownLatch sourceLocked = new CountDownLatch(1);
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowCursorCommit = new CountDownLatch(1);
        doReturn(new SourceChangePage(List.of(), null, "established-cursor-1", true))
                .when(googleDriveConnector).findChanges(sourceId, "established-cursor-0");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> cursorCreator = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("SELECT id FROM source_connections WHERE id = ? FOR UPDATE", Long.class,
                    sourceId);
            sourceLocked.countDown();
            await(allowCursorCommit);
            sourceSyncCursorJpaRepository.saveAndFlush(
                    new SourceSyncCursorEntity(sourceId, "established-cursor-0", Instant.now()));
        }));
        try {
            assertThat(sourceLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<SyncRunEntity> delayed = executor.submit(() -> {
                requestStarted.countDown();
                return sourceSyncService.sync(sourceId, owner);
            });
            assertThat(requestStarted.await(10, TimeUnit.SECONDS)).isTrue();
            allowCursorCommit.countDown();
            cursorCreator.get(10, TimeUnit.SECONDS);

            SyncRunEntity run = delayed.get(10, TimeUnit.SECONDS);
            assertThat(run.getMode()).isEqualTo("INCREMENTAL");
            assertThat(run.getStatus()).isEqualTo(SyncRunEntity.STATUS_COMPLETED);
            verify(googleDriveConnector, never()).getStartPageToken(sourceId);
            verify(googleDriveConnector, never()).listMetadata(eq(sourceId), any());
        } finally {
            allowCursorCommit.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * M09A 교정 - Provider가 같은 Page Token을 반복 반환하면(멈춰 있는 것으로 의심)
     * 무한 Loop 대신 경계 있는(Bounded) 실패로 멈춘다 - 두 번째 Fetch는 아예 일어나지
     * 않는다.
     */
    @Test
    void repeatedPageTokenStopsTheLoopWithABoundedFailureInsteadOfSpinningForever() {
        String owner = owner();
        Long sourceId = createSource(owner);
        SyncRunEntity run = syncRunLifecycle.beginRun(sourceId, owner, "INCREMENTAL");
        doReturn(new SourceChangePage(List.of(), "cursor-0", null, false))
                .when(googleDriveConnector).findChanges(sourceId, "cursor-0");

        GoogleDriveSyncJob.SyncPageLoopResult result = googleDriveSyncJob.runIncrementalSync(sourceId, run.getId(),
                "cursor-0", run.getLeaseExpiresAt(), 50);

        assertThat(result.fullyComplete()).isFalse();
        verify(googleDriveConnector, times(1)).findChanges(sourceId, "cursor-0");
    }

    /**
     * M09A 교정 - {@code maxPages}에 도달하면 무한 Loop 대신 경계 있는 실패로
     * 멈춘다. 매 Page마다 다른(진짜 전진하는) Token을 반환하므로 반복-Token
     * 감지가 아니라 Page 수 상한 자체가 멈춘 이유임을 증명한다.
     */
    @Test
    void reachingTheConfiguredPageLimitStopsTheLoopWithABoundedFailure() {
        String owner = owner();
        Long sourceId = createSource(owner);
        SyncRunEntity run = syncRunLifecycle.beginRun(sourceId, owner, "INCREMENTAL");
        for (int i = 0; i < 10; i++) {
            String from = "cursor-" + i;
            String to = "cursor-" + (i + 1);
            doReturn(new SourceChangePage(List.of(), to, null, false))
                    .when(googleDriveConnector).findChanges(sourceId, from);
        }

        GoogleDriveSyncJob.SyncPageLoopResult result = googleDriveSyncJob.runIncrementalSync(sourceId, run.getId(),
                "cursor-0", run.getLeaseExpiresAt(), 3);

        assertThat(result.fullyComplete()).as("hitting the page limit must never be reported as complete").isFalse();
        verify(googleDriveConnector, times(3)).findChanges(any(), anyString());
    }

    @Test
    void cyclicPageTokensStopBeforeRefetchingAnAlreadySeenOpaqueToken() {
        String owner = owner();
        Long sourceId = createSource(owner);
        SyncRunEntity run = syncRunLifecycle.beginRun(sourceId, owner, "INCREMENTAL");
        doReturn(new SourceChangePage(List.of(), "token-b", null, false))
                .when(googleDriveConnector).findChanges(sourceId, "token-a");
        doReturn(new SourceChangePage(List.of(), "token-a", null, false))
                .when(googleDriveConnector).findChanges(sourceId, "token-b");

        GoogleDriveSyncJob.SyncPageLoopResult result = googleDriveSyncJob.runIncrementalSync(sourceId, run.getId(),
                "token-a", run.getLeaseExpiresAt(), 50);

        assertThat(result.fullyComplete()).isFalse();
        verify(googleDriveConnector, times(1)).findChanges(sourceId, "token-a");
        verify(googleDriveConnector, times(1)).findChanges(sourceId, "token-b");
        syncRunLifecycle.abortRun(sourceId, run.getId());
    }

    @Test
    void initialListingAndCatchUpShareOnePageBudget() {
        String owner = owner();
        Long sourceId = createSource(owner);
        SyncRunEntity run = syncRunLifecycle.beginRun(sourceId, owner, "FULL");
        doReturn(new SourceMetadataPage(List.of(), null, true, true))
                .when(googleDriveConnector).listMetadata(sourceId, null);

        GoogleDriveSyncJob.SyncPageLoopResult result = googleDriveSyncJob.runInitialScan(sourceId, run.getId(),
                "budget-start", run.getLeaseExpiresAt(), 1);

        assertThat(result.fullyComplete()).isFalse();
        verify(googleDriveConnector, never()).findChanges(eq(sourceId), anyString());
        syncRunLifecycle.abortRun(sourceId, run.getId());
    }

    /**
     * M09A 교정 - 한 Page Commit Transaction 안에서 실제 DB 실패가 나면, 그 Page의
     * 모든 변경(먼저 처리된 문서/그 Outbox 이벤트 포함)과 Cursor 전진이 전부
     * Rollback된다 - 부분 반영이 남지 않는다.
     */
    @Test
    void aFailureDuringPageCommitRollsBackTheWholePageIncludingEarlierDocumentsAndTheCursor() {
        String owner = owner();
        Long sourceId = createSource(owner);
        sourceSyncCursorJpaRepository.saveAndFlush(new SourceSyncCursorEntity(sourceId, "cursor-0", Instant.now()));
        SourceDocument docOk = document(sourceId, "file-ok", "v1");
        SourceDocument docFail = document(sourceId, "file-fail", "v1");
        doReturn(new SourceChangePage(
                List.of(new SourceChangeRecord("file-ok", SourceChangeType.CHANGED, docOk),
                        new SourceChangeRecord("file-fail", SourceChangeType.CHANGED, docFail)),
                null, "cursor-1", true)).when(googleDriveConnector).findChanges(sourceId, "cursor-0");
        doReturn(okPermissions("alice")).when(googleDriveConnector).getPermissions(eq(sourceId), anyString());
        doThrow(new RuntimeException("simulated DB failure")).when(sourceDocumentJpaRepository)
                .save(org.mockito.ArgumentMatchers
                        .argThat(entity -> entity != null && "file-fail".equals(entity.getSourceDocumentId())));

        assertThatThrownBy(() -> incrementalSyncService.syncChanges(sourceId, owner))
                .isInstanceOf(RuntimeException.class);

        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, "file-ok"))
                .as("the earlier document in the same page must also be rolled back")
                .isEmpty();
        assertThat(sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, "file-fail")).isEmpty();
        assertThat(sourceSyncCursorJpaRepository.findBySourceId(sourceId).orElseThrow().getCursor())
                .as("the cursor must not advance when the page transaction rolled back")
                .isEqualTo("cursor-0");
        assertThat(eventsForSource(sourceId))
                .as("no Outbox row for the earlier (rolled back) document may survive either")
                .isEmpty();
        assertThat(securityFindingJpaRepository.findAll()).noneMatch(finding -> sourceId.equals(finding.getSourceId()));
    }

    @Test
    void normalUserSyncThenSecretPublicationCreatesHighFindingWithoutLegacyLabel() {
        String owner = owner();
        Long sourceId = createSource(owner);
        doReturn("risk-start").when(googleDriveConnector).getStartPageToken(sourceId);
        doReturn(new SourceMetadataPage(List.of(document(sourceId, "broad-file", "v1")), null, true, true))
                .when(googleDriveConnector).listMetadata(sourceId, null);
        doReturn(SourcePermissionsResult.ok(List.of(
                new SourcePermission(new SourcePrincipal("anyone", ""), "READ"))))
                .when(googleDriveConnector).getPermissions(sourceId, "broad-file");
        doReturn(new SourceChangePage(List.of(), null, "risk-cursor", true))
                .when(googleDriveConnector).findChanges(sourceId, "risk-start");

        sourceSyncService.sync(sourceId, owner);
        SourceDocumentEntity document = sourceDocumentJpaRepository
                .findBySourceAndSourceDocId(sourceId, "broad-file").orElseThrow();
        assertThat(securityLabelJpaRepository.findById(document.getId())).isEmpty();
        assertThat(securityFindingJpaRepository.findFirstByTypeAndDocumentId(
                "BROAD_PROVIDER_SHARING_HIGH_CLASSIFICATION", document.getId())).isEmpty();

        sourceSharingService.createShare(owner, sourceId, document.getId(), "SECRET", Set.of("VIEW"),
                Set.of("recipient-b"));

        assertThat(sourcePermissionJpaRepository.findByDocumentId(document.getId()))
                .singleElement().satisfies(permission -> assertThat(permission.getPrincipalType()).isEqualTo("anyone"));

        var findings = securityFindingJpaRepository.findAll().stream()
                .filter(finding -> document.getId().equals(finding.getDocumentId())).toList();
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.getSeverity()).isEqualTo("HIGH");
            assertThat(finding.getEvidence()).containsEntry("classification", "SECRET")
                    .containsEntry("principalType", "ANYONE")
                    .doesNotContainValue("sensitive.pdf").doesNotContainValue("broad-file");
        });
    }

    @Test
    void rolledBackPublicationDoesNotCreateFinding() {
        String owner = owner();
        Long sourceId = createSource(owner);
        doReturn("rollback-start").when(googleDriveConnector).getStartPageToken(sourceId);
        doReturn(new SourceMetadataPage(List.of(document(sourceId, "rollback-risk", "v1")), null, true, true))
                .when(googleDriveConnector).listMetadata(sourceId, null);
        doReturn(SourcePermissionsResult.ok(List.of(
                new SourcePermission(new SourcePrincipal("anyone", ""), "READ"))))
                .when(googleDriveConnector).getPermissions(sourceId, "rollback-risk");
        doReturn(new SourceChangePage(List.of(), null, "rollback-cursor", true))
                .when(googleDriveConnector).findChanges(sourceId, "rollback-start");
        sourceSyncService.sync(sourceId, owner);
        Long documentId = sourceDocumentJpaRepository.findBySourceAndSourceDocId(sourceId, "rollback-risk")
                .orElseThrow().getId();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            sourceSharingService.createShare(owner, sourceId, documentId, "SECRET", Set.of("VIEW"),
                    Set.of("recipient-b"));
            throw new IllegalStateException("rollback marker");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(securityFindingJpaRepository.findFirstByTypeAndDocumentId(
                "BROAD_PROVIDER_SHARING_HIGH_CLASSIFICATION", documentId)).isEmpty();
    }

    @Test
    void incrementalAclAndPublishedClassificationChangesRefreshOneFinding() {
        String owner = owner();
        Long sourceId = createSource(owner);
        doReturn("change-start").when(googleDriveConnector).getStartPageToken(sourceId);
        SourceDocument initial = document(sourceId, "changing-risk", "v1");
        doReturn(new SourceMetadataPage(List.of(initial), null, true, true))
                .when(googleDriveConnector).listMetadata(sourceId, null);
        SourcePermissionsResult domain = SourcePermissionsResult.ok(List.of(
                new SourcePermission(new SourcePrincipal("domain", "example.com"), "READ")));
        SourcePermissionsResult anyone = SourcePermissionsResult.ok(List.of(
                new SourcePermission(new SourcePrincipal("anyone", ""), "READ")));
        doReturn(domain, anyone).when(googleDriveConnector).getPermissions(sourceId, "changing-risk");
        doReturn(new SourceChangePage(List.of(), null, "change-cursor-0", true))
                .when(googleDriveConnector).findChanges(sourceId, "change-start");

        sourceSyncService.sync(sourceId, owner);
        SourceDocumentEntity document = sourceDocumentJpaRepository
                .findBySourceAndSourceDocId(sourceId, "changing-risk").orElseThrow();
        var share = sourceSharingService.createShare(owner, sourceId, document.getId(), "CONFIDENTIAL",
                Set.of("VIEW"), Set.of("recipient-b"));
        assertThat(securityFindingJpaRepository.findFirstByTypeAndDocumentId(
                "BROAD_PROVIDER_SHARING_HIGH_CLASSIFICATION", document.getId()).orElseThrow().getSeverity())
                .isEqualTo("MEDIUM");

        share = sourceSharingService.updateShare(owner, share.getId(), share.getGeneration(), "SECRET", Set.of("VIEW"),
                Set.of("recipient-b"));
        doReturn(new SourceChangePage(List.of(new SourceChangeRecord("changing-risk", SourceChangeType.CHANGED,
                document(sourceId, "changing-risk", "v1"))), null, "change-cursor-1", true))
                .when(googleDriveConnector).findChanges(sourceId, "change-cursor-0");
        sourceSyncService.sync(sourceId, owner);

        var findings = securityFindingJpaRepository.findAll().stream()
                .filter(finding -> document.getId().equals(finding.getDocumentId())
                        && "BROAD_PROVIDER_SHARING_HIGH_CLASSIFICATION".equals(finding.getType()))
                .toList();
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.getSeverity()).isEqualTo("HIGH");
            assertThat(finding.getEvidence()).containsEntry("classification", "SECRET")
                    .containsEntry("principalType", "ANYONE");
        });
    }

    private void expireRunningRun(Long sourceId) {
        int updated = jdbcTemplate.update(
                "UPDATE sync_runs SET lease_expires_at = ? WHERE source_id = ? AND status = 'RUNNING'",
                Timestamp.from(Instant.now().minusSeconds(1)), sourceId);
        assertThat(updated).as("exactly the blocked old run must be expired before the newer catalog run starts")
                .isEqualTo(1);
    }

    private void assertAllowed(String owner, Long documentId) {
        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, "VIEW", null);
        assertThat(decision.isAllowed()).isTrue();
    }

    private void assertDenied(String owner, Long documentId) {
        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, "VIEW", null);
        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.PERMISSION_DATA_UNTRUSTED);
    }

    private static UserContext userContext(String owner) {
        return new UserContext(owner, owner + "@example.com", Set.of(Role.USER), Set.of());
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

    /** outbox_events에는 sourceId 전용 Column이 없다(V001) - Payload로 이 Test의 Source에 속한 행만 골라낸다. */
    private List<OutboxEventEntity> eventsForSource(Long sourceId) {
        return outboxEventJpaRepository.findAll().stream()
                .filter(event -> sourceId.toString().equals(event.getPayload().get("sourceId")))
                .toList();
    }

    private Long createSource(String ownerSubject) {
        SourceConnectionEntity entity = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL",
                ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(entity);
        return entity.getId();
    }

    private static String owner() {
        return "owner-sync-" + OWNER_SEQUENCE.incrementAndGet();
    }

    private static SourceDocument document(Long sourceId, String externalId, String version) {
        return new SourceDocument(null, sourceId, externalId, "Doc " + externalId, "text/plain", version,
                Instant.now(), SourceDocumentState.ACTIVE, DocumentIndexStatus.PENDING, null);
    }

    private static SourcePermissionsResult okPermissions(String principalValue) {
        return SourcePermissionsResult.ok(List.of(new SourcePermission(new SourcePrincipal("user", principalValue),
                "READ")));
    }
}
