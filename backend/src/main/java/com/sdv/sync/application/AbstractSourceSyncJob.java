package com.sdv.sync.application;

import java.util.List;

/**
 * F-BE-067 (M09A 신규). Catalog Sync 공통 Template Method - {@code
 * loadCursor→fetchChanges→persist→publish}라는 공유 Workflow 불변식을 구체화한
 * Class다(Manifest가 이 File을 명시적으로 지정했다 - CLAUDE.md의 "Abstract
 * Class는 공통 Workflow invariant가 명확할 때만 사용한다" 원칙을 실제로
 * 충족한다). 실제 최초/증분 Page-Loop 구동("fetchChanges" 단계)은 구체
 * Subclass({@code com.sdv.sync.application.job.GoogleDriveSyncJob})가
 * 담당하고, 이 Class는 그 공유 불변식 중 "persist"(원자적 커밋) 단계를
 * {@link #applyPage}로 노출한다.
 *
 * <h2>실제 Transaction은 {@link SourceSyncPageWriter}에 있다</h2>
 * <p>{@link #applyPage}는 단순 위임(Delegation)이다 - 실제 {@code
 * @Transactional} 로직은 별도 Spring Bean인 {@link SourceSyncPageWriter}에
 * 있다. Subclass의 Page-Loop 메서드가 이 Class(상속받은 같은 Instance)의
 * 메서드를 직접 호출하는 것은 Spring AOP의 "self-invocation"에 해당해 Proxy
 * 기반 {@code @Transactional}이 조용히 무시되기 때문이다(Spring 공식 문서) -
 * {@link SourceSyncPageWriter} Class Javadoc 참고. 이 Class 자체에는
 * {@code @Transactional}을 두지 않는다.</p>
 */
public abstract class AbstractSourceSyncJob {

    private final SourceSyncPageWriter pageWriter;

    protected AbstractSourceSyncJob(SourceSyncPageWriter pageWriter) {
        this.pageWriter = pageWriter;
    }

    /**
     * 이미 외부에서 가져온 한 Page 분량의 변경을 원자적으로 반영한다 - 실제
     * 구현은 {@link SourceSyncPageWriter#applyPage}(별도 Bean, 진짜
     * {@code @Transactional})에 위임한다. {@code runId}는 이 Page를 만들어낸
     * 현재 Sync Run - Fencing에 쓰인다({@link SourceSyncPageWriter} 참고).
     */
    protected final SourceSyncPageWriter.PageApplyResult applyPage(Long sourceId, Long runId,
            List<SourceSyncPageWriter.PreparedChange> preparedChanges, String cursorToCommit) {
        return pageWriter.applyPage(sourceId, runId, preparedChanges, cursorToCommit);
    }
}
