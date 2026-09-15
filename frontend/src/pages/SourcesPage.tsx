import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Navigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useApiClient } from '../api/useApiClient'
import {
  authorizeGoogleSource,
  createGoogleDriveSource,
  disconnectSource,
  listSources,
  syncSource,
} from '../api/sources'
import type { SourceResponse } from '../api/sources'
import { ApiError } from '../api/client'
import { TestbedDiagnosticPanel } from '../components/TestbedDiagnosticPanel'
import { SourceSyncPanel } from '../features/sources/SourceSyncPanel'
import type { SyncOutcome } from '../features/sources/SourceSyncPanel'

type ListState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'loaded'; sources: SourceResponse[] }

/**
 * ADMIN-only Connection management (owner-scoped Google Drive Sources only -
 * Local Vault is retired/v1.4-excluded, SharePoint/S3 are contract-only server
 * side). The server independently still enforces ADMIN-only on every request
 * regardless of what this page renders.
 *
 * <h2>M16C 후속 교정 - 일반 USER를 위한 옛(Legacy) Google 복귀 경로 호환</h2>
 * <p>`.env.example`의 `GOOGLE_OAUTH_FRONTEND_RETURN_URL`(기존 문서화된 값)은
 * 여전히 이 경로(`/admin/sources`)를 가리킨다 - OAuth Callback 설정 자체는
 * 이번 교정 범위 밖이라 바꾸지 않는다. 대신 이 Route에 도착한 인증된 비관리자를
 * 즉시 {@link MyDrivePage}(자신의 개인 연결 화면)로 Redirect한다 - "관리자만
 * 사용할 수 있습니다"라는 잘못된 안내로 막지 않는다. `googleConnect` 값 중
 * 인식된(`success`/`failed`) 것만 그대로 옮기고, 그 밖의 임의 Query
 * Parameter나 Redirect 대상은 옮기지 않는다(위조된 Query로 다른 곳으로
 * 보내지지 않는다). 이 값은 알림일 뿐이다 - 실제 연결 상태는 {@link
 * MyDrivePage}가 항상 서버 목록을 다시 조회해서 보여준다(이미 그 화면의
 * 기존 `callbackHint` Effect가 이를 수행한다).</p>
 */
export function SourcesPage() {
  const { isAdmin } = useAuth()
  const [searchParams] = useSearchParams()

  if (!isAdmin) {
    const recognizedFlag = toCallbackHint(searchParams.get('googleConnect'))
    const target = recognizedFlag ? `/my-drive?googleConnect=${recognizedFlag}` : '/my-drive'
    return <Navigate to={target} replace />
  }

  return <AdminSourcesPage />
}

function AdminSourcesPage() {
  const apiClient = useApiClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const [state, setState] = useState<ListState>({ kind: 'loading' })

  const [newName, setNewName] = useState('')
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)

  const [connectingId, setConnectingId] = useState<number | null>(null)
  const [confirmingId, setConfirmingId] = useState<number | null>(null)
  const [disconnectingId, setDisconnectingId] = useState<number | null>(null)
  const [rowErrors, setRowErrors] = useState<Record<number, string>>({})

  const [syncingId, setSyncingId] = useState<number | null>(null)
  // Source 목록을 다시 불러와도(refresh) 이 결과 메시지는 별도 State이므로 사라지지 않는다.
  const [syncOutcomes, setSyncOutcomes] = useState<Record<number, SyncOutcome>>({})

  // M16A follow-up 버그 수정 - callbackHint를 매 Render마다 searchParams에서
  // 다시 계산하면, 아래 Effect가 URL에서 googleConnect를 지우자마자 바로 다음
  // Render에서 배너가 사라져 버렸다(URL 자체가 유일한 진실 공급원이었기 때문).
  // 이제 Mount 시점 값을 한 번만 State로 캡처해 URL 정리와 분리한다 - 배너는
  // 이 컴포넌트가 Unmount(예: 다른 페이지로 이동 후 재방문)될 때까지 유지되고,
  // 실제 연결 상태의 진실 공급원은 여전히 서버 목록 응답(credentialPresent)이다.
  const [callbackHint] = useState(() => toCallbackHint(searchParams.get('googleConnect')))

  // 여러 Fetch가 겹칠 때(Mount 시점의 목록 Effect와 Callback 정리 Effect가 동시에
  // 나갈 수 있다, 또는 사용자가 다시 시도를 연타할 때) 먼저 나간 요청의 응답이
  // 나중에 도착해 이미 최신인 State를 되돌리지 않도록 요청마다 증가하는 순번을
  // 매기고, 가장 최근에 발행한 요청의 응답만 반영한다.
  const fetchSequenceRef = useRef(0)

  // 실제 목록 요청 자체는 항상 비동기 콜백(.then/.catch) 안에서만 setState한다 -
  // react-hooks/set-state-in-effect가 금지하는 "Effect Body에서의 동기 setState"를
  // 만들지 않기 위함이다. 초기 State가 이미 'loading'이므로 최초 Mount는 이것만으로
  // 충분하다.
  function fetchSources() {
    const requestId = ++fetchSequenceRef.current
    listSources(apiClient)
      .then((sources) => {
        if (fetchSequenceRef.current !== requestId) return // 더 최신 요청이 이미 발행됨 - 이 응답은 버린다.
        setState({ kind: 'loaded', sources })
      })
      .catch((error: unknown) => {
        if (fetchSequenceRef.current !== requestId) return
        setState({ kind: 'error', message: describeError(error) })
      })
  }

  /** 사용자가 직접 트리거하는 재조회(재시도 버튼, 등록/연결 해제 성공 이후)에서만 쓴다 - Effect 안에서는 호출하지 않는다. */
  function refresh() {
    setState({ kind: 'loading' })
    fetchSources()
  }

  useEffect(() => {
    fetchSources()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- apiClient만 실제로 바뀌는 의존성이다.
  }, [apiClient])

  useEffect(() => {
    if (callbackHint === null) {
      return
    }
    const next = new URLSearchParams(searchParams)
    next.delete('googleConnect')
    setSearchParams(next, { replace: true })
    fetchSources()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- callbackHint는 Mount 시점 한 번만 값이 정해지는 State이므로 이 Effect도 Mount당 한 번만 실행되면 된다.
  }, [callbackHint])

  async function handleCreate(event: FormEvent) {
    event.preventDefault()
    if (creating) {
      return
    }
    const name = newName.trim()
    if (!name) {
      return
    }
    setCreating(true)
    setCreateError(null)
    try {
      await createGoogleDriveSource(apiClient, name)
      setNewName('')
      refresh()
    } catch (error) {
      setCreateError(describeError(error))
    } finally {
      setCreating(false)
    }
  }

  async function handleConnect(source: SourceResponse) {
    if (connectingId !== null) {
      return
    }
    setConnectingId(source.id)
    clearRowError(source.id)
    try {
      const result = await authorizeGoogleSource(apiClient, source.id)
      // 전체 Browser Navigation이다 - Fetch/XHR이 아니다(Google 동의 화면으로
      // 실제로 이동해야 한다). 이후 코드는 실행되지 않으므로 connectingId를
      // 여기서 되돌리지 않는다.
      redirectBrowserTo(result.authorizationUrl)
    } catch (error) {
      setRowError(source.id, describeError(error))
      setConnectingId(null)
    }
  }

  async function handleDisconnect(source: SourceResponse) {
    if (disconnectingId !== null) {
      return
    }
    setDisconnectingId(source.id)
    try {
      await disconnectSource(apiClient, source.id)
      setConfirmingId(null)
      refresh()
    } catch (error) {
      setRowError(source.id, describeError(error))
    } finally {
      setDisconnectingId(null)
    }
  }

  async function handleSync(source: SourceResponse) {
    // 동기(Synchronous) 중복 제출 방지 - React가 아직 Button을 다시 그리지 않은
    // 순간의 재클릭도 이 즉시(await 이전) 검사로 막는다(handleConnect/handleDisconnect와 동일 패턴).
    if (syncingId !== null) {
      return
    }
    setSyncingId(source.id)
    setSyncOutcomes((prev) => {
      if (!(source.id in prev)) return prev
      const next = { ...prev }
      delete next[source.id] // 새 시도가 시작됐다 - 낡은 결과 배너를 지운다.
      return next
    })
    try {
      const run = await syncSource(apiClient, source.id)
      setSyncOutcomes((prev) => ({ ...prev, [source.id]: { kind: 'result', run } }))
      refresh() // Source 목록(예: lastSyncAt)을 다시 불러온다 - 위 결과 메시지는 별도 State라 사라지지 않는다.
    } catch (error) {
      setSyncOutcomes((prev) => ({ ...prev, [source.id]: { kind: 'error', message: describeSyncError(error) } }))
    } finally {
      setSyncingId(null)
    }
  }

  function setRowError(id: number, message: string) {
    setRowErrors((prev) => ({ ...prev, [id]: message }))
  }

  function clearRowError(id: number) {
    setRowErrors((prev) => {
      if (!(id in prev)) return prev
      const next = { ...prev }
      delete next[id]
      return next
    })
  }

  return (
    <>
      <h1>연결 관리</h1>
      <p className="text-secondary">Google Drive Source를 등록하고 연결하거나, 연결을 해제합니다.</p>

      {callbackHint === 'success' && (
        <div className="status-banner">
          Google 연결 요청을 처리했습니다. 아래 목록에서 연결 상태를 확인해 주세요.
        </div>
      )}
      {callbackHint === 'failed' && (
        <div className="status-banner status-banner--error">
          Google 연결에 실패했거나 취소되었습니다. 다시 시도해 주세요.
        </div>
      )}

      <form className="card" onSubmit={handleCreate}>
        <label className="form-label" htmlFor="new-source-name">
          새 Google Drive Source 이름
        </label>
        <div className="form-row">
          <input
            id="new-source-name"
            className="input"
            value={newName}
            onChange={(event) => setNewName(event.target.value)}
            placeholder="예: 마케팅팀 공유 드라이브"
            maxLength={255}
            disabled={creating}
          />
          <button type="submit" className="btn btn--primary" disabled={creating || newName.trim().length === 0}>
            {creating ? '등록 중...' : 'Source 등록'}
          </button>
        </div>
        {createError && <div className="status-banner status-banner--error form-row">{createError}</div>}
      </form>

      {state.kind === 'loading' && <div className="status-banner">Source 목록을 불러오는 중입니다...</div>}

      {state.kind === 'error' && (
        <div className="status-banner status-banner--error">
          <p style={{ margin: 0 }}>Source 목록을 불러오지 못했습니다: {state.message}</p>
          <div className="form-row">
            <button type="button" className="btn" onClick={refresh}>
              다시 시도
            </button>
          </div>
        </div>
      )}

      {state.kind === 'loaded' && state.sources.length === 0 && (
        <div className="status-banner">등록된 Source가 없습니다. 위에서 Google Drive Source를 먼저 등록해 주세요.</div>
      )}

      {state.kind === 'loaded' && state.sources.length > 0 && (
        <ul className="source-list">
          {state.sources.map((source) => (
            <SourceRow
              key={source.id}
              source={source}
              connecting={connectingId === source.id}
              disconnecting={disconnectingId === source.id}
              confirming={confirmingId === source.id}
              syncing={syncingId === source.id}
              syncOutcome={syncOutcomes[source.id]}
              error={rowErrors[source.id]}
              onConnect={() => handleConnect(source)}
              onAskDisconnect={() => setConfirmingId(source.id)}
              onCancelDisconnect={() => setConfirmingId(null)}
              onConfirmDisconnect={() => handleDisconnect(source)}
              onSync={() => handleSync(source)}
            />
          ))}
        </ul>
      )}

      {state.kind === 'loaded' && import.meta.env.VITE_TESTBED_MODE === 'true' && (
        <TestbedDiagnosticPanel sources={state.sources} />
      )}
    </>
  )
}

interface SourceRowProps {
  source: SourceResponse
  connecting: boolean
  disconnecting: boolean
  confirming: boolean
  syncing: boolean
  syncOutcome?: SyncOutcome
  error?: string
  onConnect: () => void
  onAskDisconnect: () => void
  onCancelDisconnect: () => void
  onConfirmDisconnect: () => void
  onSync: () => void
}

function SourceRow({
  source,
  connecting,
  disconnecting,
  confirming,
  syncing,
  syncOutcome,
  error,
  onConnect,
  onAskDisconnect,
  onCancelDisconnect,
  onConfirmDisconnect,
  onSync,
}: SourceRowProps) {
  const isActive = source.status === 'ACTIVE'
  // 연결/해제 중에는 동기화를, 동기화 중에는 연결/해제를 서로 막는다 - 같은
  // Source 행에 대한 상충하는 Admin Action이 동시에 나가지 않게 한다.
  const connectDisconnectBusy = connecting || disconnecting || confirming

  return (
    <li className="source-row">
      <div className="source-row__meta">
        <span className="source-row__name">{source.name}</span>
        <StatusPill source={source} />
        {error && <span className="status-banner status-banner--error">{error}</span>}
      </div>
      <div className="source-row__actions">
        {!isActive && (
          <span className="text-secondary">
            연결 해제된 Source는 다시 연결할 수 없습니다. 필요하면 새 Source를 등록해 주세요.
          </span>
        )}
        {isActive && confirming && (
          <>
            <span>정말 연결을 해제할까요?</span>
            <button type="button" className="btn" onClick={onCancelDisconnect} disabled={disconnecting}>
              취소
            </button>
            <button type="button" className="btn btn--danger" onClick={onConfirmDisconnect} disabled={disconnecting}>
              {disconnecting ? '해제 중...' : '연결 해제 확인'}
            </button>
          </>
        )}
        {isActive && !confirming && (
          <>
            <button type="button" className="btn btn--primary" onClick={onConnect} disabled={connecting || syncing}>
              {connecting ? '연결 중...' : source.credentialPresent ? 'Google 재연결' : 'Google 연결'}
            </button>
            <button
              type="button"
              className="btn btn--danger"
              onClick={onAskDisconnect}
              disabled={disconnecting || syncing}
            >
              연결 해제
            </button>
          </>
        )}
      </div>
      {/* 자격증명 존재(credentialPresent)는 저장된 암호화 Token 행이 있다는 뜻일 뿐,
          지금 이 순간 Google에서 여전히 유효하다는 증명이 아니다 - 실제 유효성은
          동기화를 눌러봐야(또는 실제 사용 시) 드러난다. */}
      {isActive && source.credentialPresent && (
        <SourceSyncPanel syncing={syncing} disabled={connectDisconnectBusy} outcome={syncOutcome} onSync={onSync} />
      )}
    </li>
  )
}

function StatusPill({ source }: { source: SourceResponse }) {
  if (source.status !== 'ACTIVE') {
    return <span className="pill pill--disabled">연결 해제됨</span>
  }
  if (source.credentialPresent) {
    // 존재 여부일 뿐이다 - 지금 이 순간 Google에서 여전히 유효한지의 증거가 아니다.
    return <span className="pill pill--connected">Google 계정 연결됨</span>
  }
  return <span className="pill pill--pending">Google 계정 연결 필요</span>
}

function toCallbackHint(flag: string | null): 'success' | 'failed' | null {
  return flag === 'success' || flag === 'failed' ? flag : null
}

/** 별도(비-Component) 함수로 뽑아둔다 - Component 함수 안에서 직접 `window.location`을 대입하면 안 된다. */
function redirectBrowserTo(url: string): void {
  window.location.href = url
}

function describeSyncError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'AUTHENTICATION_REQUIRED':
        return '로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.'
      case 'ACCESS_DENIED':
        return '이 작업을 수행할 권한이 없습니다.'
      case 'NOT_FOUND':
        return '해당 Source를 찾을 수 없습니다.'
      case 'SYNC_ALREADY_RUNNING':
        return '이미 이 Source에 대한 동기화가 진행 중입니다. 잠시 후 다시 시도해 주세요.'
      case 'CREDENTIAL_UNAVAILABLE':
        return 'Google 계정 연결 정보를 사용할 수 없습니다. Google 연결을 다시 확인해 주세요.'
      case 'SYNC_FAILED':
        return '동기화 요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.'
      case 'VALIDATION_ERROR':
        return '요청 값을 확인해 주세요.'
      case 'NETWORK_ERROR':
        return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
      default:
        return '알 수 없는 오류가 발생했습니다.'
    }
  }
  return '알 수 없는 오류가 발생했습니다.'
}

function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'AUTHENTICATION_REQUIRED':
        // "만료"로 단정하지 않는다 - 실제로 만료 여부를 이 코드에서 확인할 수 없고,
        // 만료가 아닌 다른 이유(예: 발급된 Token 자체가 유효하지 않음)로도 같은
        // 코드가 내려올 수 있다(M16A follow-up 안전성 교정 - 실제 관찰된 사례).
        return '로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.'
      case 'ACCESS_DENIED':
        return '이 작업을 수행할 권한이 없습니다.'
      case 'NOT_FOUND':
        return '해당 Source를 찾을 수 없습니다.'
      case 'VALIDATION_ERROR':
        return '입력값을 확인해 주세요.'
      case 'OAUTH_UNAVAILABLE':
        return 'Google 연결 기능을 현재 사용할 수 없습니다. 서버 설정을 확인해 주세요.'
      case 'NETWORK_ERROR':
        return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
      default:
        return '알 수 없는 오류가 발생했습니다.'
    }
  }
  return '알 수 없는 오류가 발생했습니다.'
}
