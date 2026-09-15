package com.sdv.sync.application.job;

import com.sdv.source.domain.SourceChangePage;
import com.sdv.source.domain.SourceChangeRecord;
import com.sdv.source.domain.SourceChangeType;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourceDocumentState;
import com.sdv.source.domain.SourceMetadataPage;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.sync.application.AbstractSourceSyncJob;
import com.sdv.sync.application.SourceSyncPageWriter;
import com.sdv.sync.application.SourceSyncPageWriter.PageApplyResult;
import com.sdv.sync.application.SourceSyncPageWriter.PreparedChange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F-BE-196 (M09A 신규, M09A 교정 - 최초 스캔 후 변경 Catch-up/경계 있는
 * Page-Loop/Run Fencing 반영). {@link AbstractSourceSyncJob}의 Google Drive
 * 구체 구현 - v1.4 유일한 Core Connector({@code CLAUDE.md} §제품정의)이므로
 * {@link SourceConnectorRegistry}를 거치지 않고 구체 {@link GoogleDriveConnector}를
 * 직접 주입받는다({@code getStartPageToken}이 그 Connector에만 있는 Google
 * 전용 공개 메서드이기 때문 - {@link
 * com.sdv.source.application.port.DocumentSourceConnector} 계약에는 없다).
 *
 * <h2>Fetch(외부 호출)와 Commit(DB Transaction)의 분리</h2>
 * <p>이 Class의 {@code run*} 메서드는 각 Page마다: (1) Google 호출로 Metadata/
 * 변경/권한을 가져오고({@code fetch*}/{@code prepare*} - Transaction 밖), (2)
 * 그 결과만으로 {@link AbstractSourceSyncJob#applyPage}(원자적 Transaction)를
 * 호출한다. 어떤 DB Row Lock도 Google 호출 동안 열려있지 않는다.</p>
 *
 * <h2>최초 스캔은 Catch-up까지 끝나야 완료다(교정)</h2>
 * <p>{@link #runInitialScan}은 Whole-Drive Metadata Discovery가 완전히
 * 끝난 뒤에도 즉시 끝나지 않는다 - 스캔이 시작하기 전에 캡처해둔 {@code
 * startCursor}부터 그 시점까지 쌓인 변경 Feed를 같은 Run 안에서 마저
 * 소진(Catch-up)한 뒤에만 {@code fullyComplete=true}를 보고한다(이전 교정
 * 전에는 Metadata 완료만으로 즉시 끝내, 스캔 도중 바뀌거나 삭제된 파일이
 * 다음 수동 Sync까지 반영되지 않았다). {@link IncrementalSyncService#syncChanges}
 * 를 다시 호출(=새 {@code RUNNING} 행)하지 않고, 이 Run 안에서
 * {@link #drainChanges}를 그대로 재사용한다.</p>
 *
 * <h2>경계(Bounded) Page-Loop</h2>
 * <p>{@code maxPages}에 도달하거나 {@code deadline}을 넘기거나 Provider가 같은
 * Page Token을 반복해서 돌려주면(멈춰 있는 상태로 의심) 즉시 멈추고 정직한
 * 부분 실패를 보고한다 - 무한 Loop을 절대 만들지 않는다.</p>
 */
@Component
public class GoogleDriveSyncJob extends AbstractSourceSyncJob {

    private final GoogleDriveConnector googleDriveConnector;
    private final Clock clock;

    @Autowired
    public GoogleDriveSyncJob(GoogleDriveConnector googleDriveConnector, SourceSyncPageWriter pageWriter) {
        this(googleDriveConnector, pageWriter, Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}(Deadline 판정 결정론화)을 직접 주입하기 위한 패키지 전용 생성자. */
    GoogleDriveSyncJob(GoogleDriveConnector googleDriveConnector, SourceSyncPageWriter pageWriter, Clock clock) {
        super(pageWriter);
        this.googleDriveConnector = googleDriveConnector;
        this.clock = clock;
    }

    /** 최초 Sync 시작 "전"에 호출한다 - 이후 Whole-Drive 목록 훑기와 별개로, 다음 증분의 출발점이 된다. */
    public String captureStartCursor(Long sourceId) {
        return googleDriveConnector.getStartPageToken(sourceId);
    }

    /**
     * 최초 전체 스캔(Whole-Drive Metadata Discovery) + 그 시점까지의 변경
     * Catch-up을 끝까지 수행한다. {@code startCursor}는 스캔을 시작하기
     * "전"에 {@link #captureStartCursor}로 미리 받아둔 값이어야 한다 - 모든
     * Metadata Page가 {@link SourceMetadataPage#isComplete()}인 채로 끝까지
     * 소진되고, 그 뒤 {@code startCursor}부터의 변경 Feed까지 완전히
     * 소진됐을 때만 {@code fullyComplete=true}를 반환한다(부분/불완전 Page나
     * Catch-up 실패가 하나라도 있으면 "완료"로 표시하지 않는다 - 그 경우 이
     * Sync를 처음부터 다시 실행해야 한다는 뜻이며, 이미 반영된 문서 행 자체는
     * 유지된다 - 재실행은 낭비지만 안전하다, 재실행 시 Version이 같은 문서는
     * 재반영돼도 새 이벤트를 만들지 않는다).
     */
    public SyncPageLoopResult runInitialScan(Long sourceId, Long runId, String startCursor, Instant deadline,
            int maxPages) {
        String pageToken = null;
        boolean allComplete = true;
        boolean sourceActive = true;
        boolean runOwned = true;
        int changed = 0;
        int removed = 0;
        int permissionFailures = 0;
        int pagesProcessed = 0;
        Set<String> seenPageTokens = new HashSet<>();
        while (true) {
            if (pagesProcessed >= maxPages || deadlineReached(deadline) || !seenPageTokens.add(pageToken)) {
                allComplete = false;
                break;
            }
            SourceMetadataPage page = fetchMetadataPage(sourceId, pageToken);
            pagesProcessed++;
            if (deadlineReached(deadline)) {
                allComplete = false;
                break;
            }
            if (!page.isComplete()) {
                allComplete = false;
            }
            PagePreparation preparation = prepareMetadataPage(sourceId, page, deadline);
            if (!preparation.complete()) {
                allComplete = false;
                break;
            }
            // Metadata Discovery 자체의 Page Token은 changes.list Cursor가 아니다 - 마지막
            // Page에서만, 그리고 지금까지 전부 Complete였을 때만 startCursor를 넘긴다(아래
            // Catch-up 단계가 그 값부터 이어받는다). 중간 Page에서는 Cursor를 전혀 Commit하지 않는다.
            String cursorForThisPage = (page.isLastPage() && allComplete) ? startCursor : null;
            PageApplyResult result = applyPage(sourceId, runId, preparation.changes(), cursorForThisPage);
            if (!result.sourceActive()) {
                sourceActive = false;
                break;
            }
            if (!result.runOwned()) {
                runOwned = false;
                break;
            }
            changed += result.changed();
            removed += result.removed();
            permissionFailures += result.permissionFailures();
            if (result.permissionFailures() > 0) {
                // 이 Page의 ACL 실패 - 재시도 가능하게 여기서 멈춘다(SourceSyncPageWriter가 이미
                // 이 Page의 Cursor Commit 자체를 건너뛰었다).
                allComplete = false;
                break;
            }
            if (page.isLastPage()) {
                break;
            }
            pageToken = page.nextPageToken();
        }

        if (!sourceActive || !runOwned || !allComplete) {
            return new SyncPageLoopResult(sourceActive, runOwned, false, changed, removed, permissionFailures);
        }

        // Metadata 훑기가 완전히 끝났다 - 이제 스캔이 진행되는 동안 쌓였을 변경을 같은 Run
        // 안에서 마저 소진한다(새 RUNNING 행을 만들지 않는다).
        SyncPageLoopResult catchUp = drainChanges(sourceId, runId, startCursor, deadline, maxPages - pagesProcessed);
        return new SyncPageLoopResult(catchUp.sourceActive(), catchUp.runOwned(), catchUp.fullyComplete(),
                changed + catchUp.changed(), removed + catchUp.removed(),
                permissionFailures + catchUp.permissionFailures());
    }

    /**
     * 증분 변경 Feed를 저장된 Cursor부터 끝까지 소진한다 - 실제로는
     * {@link #drainChanges}를 그대로 재사용한다({@link #runInitialScan}의
     * Catch-up 단계와 완전히 같은 Page-Loop/Fencing/경계 규칙을 공유한다).
     */
    public SyncPageLoopResult runIncrementalSync(Long sourceId, Long runId, String startingCursor, Instant deadline,
            int maxPages) {
        return drainChanges(sourceId, runId, startingCursor, deadline, maxPages);
    }

    /**
     * 변경 Feed를 {@code startingCursor}부터 끝까지 소진하는 공유 Page-Loop.
     * 각 Page마다 Cursor를 전진시킨다(중간 Page는 {@code nextPageToken}, 마지막
     * Page는 {@code newStartPageToken}) - 재시작/중복 Trigger 시 이미 처리한
     * Page를 다시 훑지 않게 한다. {@code maxPages}에 도달하거나 {@code deadline}을
     * 넘기거나, Provider가 같은 Page Token을 반복 반환하면(멈춰 있는 것으로
     * 의심) 정직한 부분 실패로 멈춘다.
     */
    private SyncPageLoopResult drainChanges(Long sourceId, Long runId, String startingCursor, Instant deadline,
            int maxPages) {
        String pageToken = startingCursor;
        boolean sourceActive = true;
        boolean runOwned = true;
        boolean fullyComplete = true;
        int changed = 0;
        int removed = 0;
        int permissionFailures = 0;
        int pagesProcessed = 0;
        Set<String> seenPageTokens = new HashSet<>();
        while (true) {
            if (pagesProcessed >= maxPages || deadlineReached(deadline)) {
                fullyComplete = false;
                break;
            }
            if (!seenPageTokens.add(pageToken)) {
                // Provider가 같은 Page Token을 반복 반환한다 - 멈춰 있는 것으로 의심하고
                // 무한 Loop 대신 정직한 부분 실패로 멈춘다(Bounded Recovery).
                fullyComplete = false;
                break;
            }
            SourceChangePage page = fetchChangesPage(sourceId, pageToken);
            pagesProcessed++;
            if (deadlineReached(deadline)) {
                fullyComplete = false;
                break;
            }
            PagePreparation preparation = prepareChangePage(sourceId, page, deadline);
            if (!preparation.complete()) {
                fullyComplete = false;
                break;
            }
            String cursorForThisPage = page.isLastPage() ? page.newStartPageToken() : page.nextPageToken();
            PageApplyResult result = applyPage(sourceId, runId, preparation.changes(), cursorForThisPage);
            if (!result.sourceActive()) {
                sourceActive = false;
                fullyComplete = false;
                break;
            }
            if (!result.runOwned()) {
                runOwned = false;
                fullyComplete = false;
                break;
            }
            changed += result.changed();
            removed += result.removed();
            permissionFailures += result.permissionFailures();
            if (result.permissionFailures() > 0) {
                fullyComplete = false;
                break;
            }
            if (page.isLastPage()) {
                break;
            }
            pageToken = page.nextPageToken();
        }
        return new SyncPageLoopResult(sourceActive, runOwned, fullyComplete, changed, removed, permissionFailures);
    }

    private SourceMetadataPage fetchMetadataPage(Long sourceId, String pageToken) {
        return googleDriveConnector.listMetadata(sourceId, pageToken);
    }

    private SourceChangePage fetchChangesPage(Long sourceId, String pageToken) {
        return googleDriveConnector.findChanges(sourceId, pageToken);
    }

    private boolean deadlineReached(Instant deadline) {
        return !clock.instant().isBefore(deadline);
    }

    private PagePreparation prepareMetadataPage(Long sourceId, SourceMetadataPage page, Instant deadline) {
        List<PreparedChange> prepared = new ArrayList<>();
        for (SourceDocument document : page.documents()) {
            if (deadlineReached(deadline)) {
                return PagePreparation.incomplete();
            }
            prepared.add(prepareChangedDocument(sourceId, document));
            if (deadlineReached(deadline)) {
                return PagePreparation.incomplete();
            }
        }
        return PagePreparation.complete(prepared);
    }

    private PagePreparation prepareChangePage(Long sourceId, SourceChangePage page, Instant deadline) {
        List<PreparedChange> prepared = new ArrayList<>();
        for (SourceChangeRecord record : page.changes()) {
            if (deadlineReached(deadline)) {
                return PagePreparation.incomplete();
            }
            if (record.type() == SourceChangeType.CHANGED) {
                prepared.add(prepareChangedDocument(sourceId, record.document()));
            } else {
                prepared.add(new PreparedChange(record, null));
            }
            if (deadlineReached(deadline)) {
                return PagePreparation.incomplete();
            }
        }
        return PagePreparation.complete(prepared);
    }

    /** Trashed 문서는 권한을 조회하지 않는다(어차피 즉시 Retire된다 - {@link AbstractSourceSyncJob} 참고). */
    private PreparedChange prepareChangedDocument(Long sourceId, SourceDocument document) {
        SourceChangeRecord record = new SourceChangeRecord(document.getSourceDocumentId(), SourceChangeType.CHANGED,
                document);
        if (document.getState() == SourceDocumentState.DELETED) {
            return new PreparedChange(record, null);
        }
        SourcePermissionsResult permissions = googleDriveConnector.getPermissions(sourceId,
                document.getSourceDocumentId());
        return new PreparedChange(record, permissions);
    }

    private record PagePreparation(List<PreparedChange> changes, boolean complete) {
        static PagePreparation complete(List<PreparedChange> changes) {
            return new PagePreparation(List.copyOf(changes), true);
        }

        static PagePreparation incomplete() {
            return new PagePreparation(List.of(), false);
        }
    }

    /**
     * 한 Sync 실행(여러 Page, 최초 스캔의 경우 Catch-up까지 포함) 전체의 집계 결과.
     * {@code sourceActive=false}면 도중에 Source가 비활성화됐다. {@code runOwned=false}면
     * 이 Run의 Lease가 지나 다른 요청에 의해 이미 회수됐다. {@code fullyComplete}는
     * Metadata/Catch-up 전부가 끊김 없이 끝까지 소진됐을 때만 true다.
     */
    public record SyncPageLoopResult(boolean sourceActive, boolean runOwned, boolean fullyComplete, int changed,
            int removed, int permissionFailures) {
    }
}
