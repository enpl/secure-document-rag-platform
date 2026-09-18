import type { SyncRunResponse } from '../../api/sources'

export type SyncOutcome = { kind: 'result'; run: SyncRunResponse } | { kind: 'error'; message: string }

interface SourceSyncPanelProps {
  syncing: boolean
  /** Disabled for reasons other than `syncing` itself - e.g. a connect/disconnect is pending on the same row. */
  disabled: boolean
  outcome?: SyncOutcome
  onSync: () => void
}

/**
 * F-FE-006 - ADMIN이 이미 연결된 Google Drive Source의 Metadata/권한(ACL)
 * Catalog를 수동으로 다시 동기화한다({@code POST /api/admin/sources/{id}/sync},
 * F-BE-062). 이 Action은 Metadata/권한 동기화이지 Content 색인이 아니다 - 문구
 * 어디에도 "색인"이라는 말을 쓰지 않는다.
 *
 * <p>이 API는 동기 호출이다 - 진행률/Job 상태를 알려주는 별도 API가 없으므로,
 * 완료될 때까지 하나의 부정형(Indeterminate) "진행 중" 표시만 보여준다. 가짜
 * 퍼센트, 자체적으로 지어낸 Polling/상태 조회, 자동 재시도를 절대 만들지
 * 않는다 - 응답이 오지 않는 것이 "서버가 멈췄다"는 증거도 아니므로, 사용자가
 * 다시 시도하려면 명시적으로 버튼을 다시 눌러야 한다(자동 반복 없음).</p>
 */
export function SourceSyncPanel({ syncing, disabled, outcome, onSync }: SourceSyncPanelProps) {
  return (
    <div className="sync-panel">
      <button type="button" className="btn" onClick={onSync} disabled={disabled || syncing} aria-busy={syncing}>
        {syncing ? '동기화 중...' : '메타데이터 동기화'}
      </button>
      {outcome && <SyncOutcomeBanner outcome={outcome} />}
    </div>
  )
}

function SyncOutcomeBanner({ outcome }: { outcome: SyncOutcome }) {
  if (outcome.kind === 'error') {
    return <div className="status-banner status-banner--error">{outcome.message}</div>
  }
  const { run } = outcome
  // HTTP 200 자체는 "완전히 끝났다"는 증거가 아니다 - 실제 status 필드를 봐야 한다.
  const complete = run.status === 'COMPLETED'
  // M17 프론트 고급화 - 완전 실패(FAILED/ABANDONED)와 부분 실패/응답 지연
  // (PARTIAL_FAILURE/RUNNING)을 시각적으로 구분한다(경고 의미를 약화하지
  // 않는다 - 둘 다 여전히 눈에 띄는 색이며, 완료만 중립으로 표시한다). 값
  // 자체는 이미 서버가 돌려주는 run.status를 그대로 쓴다 - 새 상태를
  // 지어내지 않는다.
  const severity = complete ? null : run.status === 'FAILED' || run.status === 'ABANDONED' ? 'error' : 'warning'
  return (
    <div className={severity ? `status-banner status-banner--${severity}` : 'status-banner'}>
      <p style={{ margin: 0 }}>
        {complete
          ? '메타데이터/권한 동기화가 완료됐습니다.'
          : `동기화가 완전히 끝나지 않았습니다 (상태: ${describeSyncStatus(run.status)}).`}
      </p>
      <p style={{ margin: '4px 0 0' }}>
        처리 {run.total}건 중 성공 {run.success}건 / 실패 {run.failed}건
        <br />
        <span className="text-secondary">
          이 수치는 동기화 처리 건수이며, 고유 파일 수나 AI 답변 가능 문서 수와 다를 수 있습니다.
        </span>
      </p>
    </div>
  )
}

function describeSyncStatus(status: string): string {
  switch (status) {
    case 'PARTIAL_FAILURE':
      return '일부 실패'
    case 'FAILED':
      return '실패'
    case 'ABANDONED':
      return '중단됨'
    case 'RUNNING':
      return '진행 중 응답 지연'
    default:
      return status
  }
}
