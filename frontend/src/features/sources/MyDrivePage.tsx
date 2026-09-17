import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useApiClient } from '../../api/useApiClient'
import {
  authorizeGoogleSource,
  createMyGoogleDriveSource,
  disconnectMyDriveSource,
  listMyDriveFiles,
  listMyDriveSources,
  syncMyDriveSource,
} from '../../api/sources'
import type { SourceFileResponse, SourceResponse } from '../../api/sources'
import { listMyShares, unshare } from '../../api/shares'
import type { ShareResponse } from '../../api/shares'
import { ApiError } from '../../api/client'
import { SourceSyncPanel } from './SourceSyncPanel'
import type { SyncOutcome } from './SourceSyncPanel'
import { ShareSettingsDialog } from './ShareSettingsDialog'
import type { ShareTarget } from './ShareSettingsDialog'
import { navigateToGoogleAuthorization } from './googleAuthorizationNavigation'

const PICKER_PAGE_SIZE = 50

type ConnectionsState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'loaded'; sources: SourceResponse[] }

type PickerState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'loaded'; items: SourceFileResponse[]; hasMore: boolean }

type SharesState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'loaded'; shares: ShareResponse[] }

type DialogState = { mode: 'create'; files: ShareTarget[] } | { mode: 'edit'; share: ShareResponse } | null

type CallbackHint = 'success' | 'failed' | null

type AuthorizationReturnState =
  | { kind: 'idle' }
  | { kind: 'checking' }
  | { kind: 'confirmed'; message: string }
  | { kind: 'incomplete'; message: string }
  | { kind: 'failed'; message: string }
  | { kind: 'error'; message: string }

interface PendingAuthorizationAttempt {
  id: number
  sourceId: number
  subject: string | null
  credentialPresentBefore: boolean
}

interface AuthorizationRecoveryContext {
  hint: CallbackHint
  attempt: PendingAuthorizationAttempt | null
}

/**
 * M16C 신규(SHR-001/002/003, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.1/§2A.13) -
 * "내 Drive": 일반 USER와 ADMIN(자신의 개인 연결에 한해) 모두가 쓰는 화면.
 * 소유자 전용 API(`GET/POST /api/sources`, `DELETE /api/sources/{id}`, `POST
 * /api/sources/{id}/sync`, `GET /api/sources/{id}/files`)만 호출한다 - ADMIN
 * 전용 `/api/admin/sources/**`(연결 관리 화면, {@code SourcesPage})와는 완전히
 * 별개다.
 *
 * <p>연결/동기화/탐색/체크박스 선택 그 자체는 어떤 파일도 게시(공유)하지
 * 않는다 - 실제 게시는 사용자가 "선택한 파일 공유하기"를 누르고 {@link
 * ShareSettingsDialog}에서 명시적으로 확인해야만 일어난다.</p>
 */
export function MyDrivePage() {
  const { subject } = useAuth()
  const apiClient = useApiClient()
  const [searchParams, setSearchParams] = useSearchParams()

  // ---------------- 연결 관리 ----------------
  const [connections, setConnections] = useState<ConnectionsState>({ kind: 'loading' })
  const [newName, setNewName] = useState('')
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  const [connectingId, setConnectingId] = useState<number | null>(null)
  const [confirmingId, setConfirmingId] = useState<number | null>(null)
  const [disconnectingId, setDisconnectingId] = useState<number | null>(null)
  const [rowErrors, setRowErrors] = useState<Record<number, string>>({})
  const [syncingId, setSyncingId] = useState<number | null>(null)
  const [syncOutcomes, setSyncOutcomes] = useState<Record<number, SyncOutcome>>({})
  const [callbackHint] = useState(() => toCallbackHint(searchParams.get('googleConnect')))
  const [authorizationReturn, setAuthorizationReturn] = useState<AuthorizationReturnState>({ kind: 'idle' })
  const connectionsFetchSeq = useRef(0)
  const mountedRef = useRef(false)
  const currentSubjectRef = useRef(subject)
  const authorizationAttemptSeq = useRef(0)
  const pendingAuthorizationRef = useRef<PendingAuthorizationAttempt | null>(null)
  const recoveryContextRef = useRef<AuthorizationRecoveryContext | null>(null)
  const recoveryInFlightRef = useRef(false)
  const authorizeControllerRef = useRef<AbortController | null>(null)
  const connectionsControllerRef = useRef<AbortController | null>(null)
  currentSubjectRef.current = subject

  // ---------------- 비공개 파일 선택기 ----------------
  const [browsingSourceId, setBrowsingSourceId] = useState<number | null>(null)
  const [pickerPage, setPickerPage] = useState(0)
  const [picker, setPicker] = useState<PickerState>({ kind: 'idle' })
  const [selected, setSelected] = useState<Map<number, ShareTarget>>(new Map())
  const pickerFetchSeq = useRef(0)

  // ---------------- 공유 대화상자 / 내가 게시한 공유 ----------------
  const [dialog, setDialog] = useState<DialogState>(null)
  const [shares, setShares] = useState<SharesState>({ kind: 'loading' })
  const [unsharingId, setUnsharingId] = useState<number | null>(null)
  const [shareRowErrors, setShareRowErrors] = useState<Record<number, string>>({})

  function fetchConnections() {
    const requestId = ++connectionsFetchSeq.current
    connectionsControllerRef.current?.abort()
    const controller = new AbortController()
    connectionsControllerRef.current = controller
    listMyDriveSources(apiClient, controller.signal)
      .then((sources) => {
        if (!mountedRef.current || controller.signal.aborted || connectionsFetchSeq.current !== requestId) return
        setConnections({ kind: 'loaded', sources })
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || !mountedRef.current || connectionsFetchSeq.current !== requestId) return
        setConnections({ kind: 'error', message: describeSourceError(error) })
      })
  }

  function refreshConnections() {
    setConnections({ kind: 'loading' })
    fetchConnections()
  }

  function fetchShares() {
    listMyShares(apiClient)
      .then((list) => setShares({ kind: 'loaded', shares: list }))
      .catch((error: unknown) => setShares({ kind: 'error', message: describeShareListError(error) }))
  }

  useEffect(() => {
    mountedRef.current = true
    fetchShares()

    if (callbackHint === null) {
      fetchConnections()
    } else {
      const next = new URLSearchParams(searchParams)
      next.delete('googleConnect')
      setSearchParams(next, { replace: true })
      void reconcileAuthorizationReturn({ hint: callbackHint, attempt: null })
    }

    function handlePageShow() {
      const attempt = pendingAuthorizationRef.current
      if (attempt === null) return
      void reconcileAuthorizationReturn({ hint: null, attempt })
    }

    window.addEventListener('pageshow', handlePageShow)
    return () => {
      mountedRef.current = false
      window.removeEventListener('pageshow', handlePageShow)
      authorizeControllerRef.current?.abort()
      connectionsControllerRef.current?.abort()
      authorizationAttemptSeq.current += 1
      connectionsFetchSeq.current += 1
      pendingAuthorizationRef.current = null
      recoveryInFlightRef.current = false
    }
    // callbackHint는 이 Mount의 최초 query에서 고정되고 App.tsx의 key={subject}가 계정 전환 시 Remount한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [apiClient, subject])

  useEffect(() => {
    if (browsingSourceId === null) {
      setPicker({ kind: 'idle' })
      return
    }
    const requestId = ++pickerFetchSeq.current
    setPicker({ kind: 'loading' })
    listMyDriveFiles(apiClient, browsingSourceId, pickerPage, PICKER_PAGE_SIZE)
      .then((page) => {
        if (pickerFetchSeq.current !== requestId) return
        setPicker({ kind: 'loaded', items: page.items, hasMore: page.hasMore })
      })
      .catch((error: unknown) => {
        if (pickerFetchSeq.current !== requestId) return
        setPicker({ kind: 'error', message: describeSourceError(error) })
      })
  }, [apiClient, browsingSourceId, pickerPage])

  async function handleCreate(event: FormEvent) {
    event.preventDefault()
    if (creating) return
    const name = newName.trim()
    if (!name) return
    setCreating(true)
    setCreateError(null)
    try {
      await createMyGoogleDriveSource(apiClient, name)
      setNewName('')
      refreshConnections()
    } catch (error) {
      setCreateError(describeSourceError(error))
    } finally {
      setCreating(false)
    }
  }

  async function handleConnect(source: SourceResponse) {
    if (connectingId !== null) return
    authorizeControllerRef.current?.abort()
    const controller = new AbortController()
    authorizeControllerRef.current = controller
    const attempt: PendingAuthorizationAttempt = {
      id: ++authorizationAttemptSeq.current,
      sourceId: source.id,
      subject,
      credentialPresentBefore: source.credentialPresent,
    }
    pendingAuthorizationRef.current = attempt
    setConnectingId(source.id)
    setAuthorizationReturn({ kind: 'idle' })
    clearRowError(source.id)
    try {
      const result = await authorizeGoogleSource(apiClient, source.id, controller.signal)
      if (!isCurrentAuthorizationAttempt(attempt, controller)) return
      navigateToGoogleAuthorization(result.authorizationUrl)
    } catch (error) {
      if (isAbortError(error) || !isCurrentAuthorizationAttempt(attempt, controller)) return
      pendingAuthorizationRef.current = null
      setRowError(source.id, describeSourceError(error))
      setConnectingId(null)
    }
  }

  function isCurrentAuthorizationAttempt(attempt: PendingAuthorizationAttempt, controller: AbortController) {
    return (
      mountedRef.current &&
      !controller.signal.aborted &&
      authorizationAttemptSeq.current === attempt.id &&
      currentSubjectRef.current === attempt.subject &&
      pendingAuthorizationRef.current?.id === attempt.id
    )
  }

  async function reconcileAuthorizationReturn(context: AuthorizationRecoveryContext) {
    if (recoveryInFlightRef.current) return
    recoveryInFlightRef.current = true
    recoveryContextRef.current = context
    connectionsControllerRef.current?.abort()
    const controller = new AbortController()
    connectionsControllerRef.current = controller
    const requestId = ++connectionsFetchSeq.current
    const expectedSubject = currentSubjectRef.current
    setAuthorizationReturn({ kind: 'checking' })
    if (context.attempt !== null) {
      setConnectingId(context.attempt.sourceId)
    }

    try {
      const sources = await listMyDriveSources(apiClient, controller.signal)
      if (
        !mountedRef.current ||
        controller.signal.aborted ||
        connectionsFetchSeq.current !== requestId ||
        currentSubjectRef.current !== expectedSubject
      ) {
        return
      }
      setConnections({ kind: 'loaded', sources })
      setAuthorizationReturn(describeAuthorizationReturn(context, sources))
      pendingAuthorizationRef.current = null
    } catch (error) {
      if (
        isAbortError(error) ||
        !mountedRef.current ||
        connectionsFetchSeq.current !== requestId ||
        currentSubjectRef.current !== expectedSubject
      ) {
        return
      }
      setAuthorizationReturn({
        kind: 'error',
        message: `Google 연결 상태를 확인하지 못했습니다. ${describeSourceError(error)}`,
      })
    } finally {
      if (mountedRef.current && connectionsFetchSeq.current === requestId) {
        recoveryInFlightRef.current = false
        setConnectingId(null)
      }
    }
  }

  function retryAuthorizationReconciliation() {
    const context = recoveryContextRef.current
    if (context !== null) {
      void reconcileAuthorizationReturn(context)
    }
  }

  async function handleDisconnect(source: SourceResponse) {
    if (disconnectingId !== null) return
    setDisconnectingId(source.id)
    try {
      await disconnectMyDriveSource(apiClient, source.id)
      setConfirmingId(null)
      if (browsingSourceId === source.id) {
        setBrowsingSourceId(null)
      }
      refreshConnections()
    } catch (error) {
      setRowError(source.id, describeSourceError(error))
    } finally {
      setDisconnectingId(null)
    }
  }

  async function handleSync(source: SourceResponse) {
    if (syncingId !== null) return
    setSyncingId(source.id)
    setSyncOutcomes((prev) => {
      if (!(source.id in prev)) return prev
      const next = { ...prev }
      delete next[source.id]
      return next
    })
    try {
      const run = await syncMyDriveSource(apiClient, source.id)
      setSyncOutcomes((prev) => ({ ...prev, [source.id]: { kind: 'result', run } }))
      refreshConnections()
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

  function toggleSelected(source: SourceResponse, file: SourceFileResponse) {
    setSelected((prev) => {
      const next = new Map(prev)
      if (next.has(file.documentId)) {
        next.delete(file.documentId)
      } else {
        next.set(file.documentId, { sourceId: source.id, documentId: file.documentId, name: file.name })
      }
      return next
    })
  }

  function clearSelection() {
    setSelected(new Map())
  }

  async function handleUnshare(share: ShareResponse) {
    if (unsharingId !== null) return
    setUnsharingId(share.id)
    try {
      await unshare(apiClient, share.id)
      fetchShares()
    } catch (error) {
      setShareRowErrors((prev) => ({ ...prev, [share.id]: describeShareListError(error) }))
    } finally {
      setUnsharingId(null)
    }
  }

  const browsingSource =
    connections.kind === 'loaded' ? connections.sources.find((s) => s.id === browsingSourceId) ?? null : null

  return (
    <>
      <h1>내 Drive</h1>
      <p className="text-secondary">
        내 Google Drive를 연결하고, 원하는 파일만 골라 지정한 사람에게 공유합니다. 연결/동기화/탐색은 그 자체로는
        아무 파일도 다른 사람에게 공개하지 않습니다 - 실제 공유는 아래에서 파일을 고르고 명시적으로 확인해야만
        일어납니다.
      </p>

      <ShareIdCard subject={subject} />

      {authorizationReturn.kind === 'checking' && (
        <div className="status-banner">Google 연결 상태를 다시 확인하는 중입니다...</div>
      )}
      {(authorizationReturn.kind === 'confirmed' || authorizationReturn.kind === 'incomplete') && (
        <div className="status-banner">{authorizationReturn.message}</div>
      )}
      {authorizationReturn.kind === 'failed' && (
        <div className="status-banner status-banner--error">{authorizationReturn.message}</div>
      )}
      {authorizationReturn.kind === 'error' && (
        <div className="status-banner status-banner--error">
          <p style={{ margin: 0 }}>{authorizationReturn.message}</p>
          <div className="form-row">
            <button type="button" className="btn" onClick={retryAuthorizationReconciliation}>
              연결 상태 다시 확인
            </button>
          </div>
        </div>
      )}

      <form className="card" onSubmit={handleCreate}>
        <label className="form-label" htmlFor="my-drive-new-name">
          새 Google Drive 연결 이름
        </label>
        <div className="form-row">
          <input
            id="my-drive-new-name"
            className="input"
            value={newName}
            onChange={(event) => setNewName(event.target.value)}
            placeholder="예: 내 드라이브"
            maxLength={255}
            disabled={creating}
          />
          <button type="submit" className="btn btn--primary" disabled={creating || newName.trim().length === 0}>
            {creating ? '등록 중...' : '연결 등록'}
          </button>
        </div>
        {createError && <div className="status-banner status-banner--error form-row">{createError}</div>}
      </form>

      {connections.kind === 'loading' && <div className="status-banner">연결 목록을 불러오는 중입니다...</div>}
      {connections.kind === 'error' && (
        <div className="status-banner status-banner--error">
          <p style={{ margin: 0 }}>연결 목록을 불러오지 못했습니다: {connections.message}</p>
          <div className="form-row">
            <button type="button" className="btn" onClick={refreshConnections}>
              다시 시도
            </button>
          </div>
        </div>
      )}
      {connections.kind === 'loaded' && connections.sources.length === 0 && (
        <div className="status-banner">등록된 연결이 없습니다. 위에서 먼저 등록해 주세요.</div>
      )}

      {connections.kind === 'loaded' && connections.sources.length > 0 && (
        <ul className="source-list">
          {connections.sources.map((source) => (
            <ConnectionRow
              key={source.id}
              source={source}
              connecting={connectingId === source.id}
              disconnecting={disconnectingId === source.id}
              confirming={confirmingId === source.id}
              syncing={syncingId === source.id}
              syncOutcome={syncOutcomes[source.id]}
              error={rowErrors[source.id]}
              browsing={browsingSourceId === source.id}
              onConnect={() => handleConnect(source)}
              onAskDisconnect={() => setConfirmingId(source.id)}
              onCancelDisconnect={() => setConfirmingId(null)}
              onConfirmDisconnect={() => handleDisconnect(source)}
              onSync={() => handleSync(source)}
              onBrowse={() => {
                setBrowsingSourceId(source.id)
                setPickerPage(0)
              }}
            />
          ))}
        </ul>
      )}

      {browsingSource && (
        <FilePicker
          source={browsingSource}
          picker={picker}
          page={pickerPage}
          selected={selected}
          onToggle={(file) => toggleSelected(browsingSource, file)}
          onPrevious={() => setPickerPage((p) => Math.max(0, p - 1))}
          onNext={() => setPickerPage((p) => p + 1)}
          onClose={() => setBrowsingSourceId(null)}
        />
      )}

      {selected.size > 0 && (
        <div className="status-banner form-row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
          <span>선택한 파일 {selected.size}개</span>
          <div className="form-row" style={{ margin: 0 }}>
            <button type="button" className="btn" onClick={clearSelection}>
              선택 해제
            </button>
            <button
              type="button"
              className="btn btn--primary"
              onClick={() => setDialog({ mode: 'create', files: Array.from(selected.values()) })}
            >
              선택한 파일 공유하기
            </button>
          </div>
        </div>
      )}

      <h2>내가 게시한 공유</h2>
      {shares.kind === 'loading' && <div className="status-banner">공유 목록을 불러오는 중입니다...</div>}
      {shares.kind === 'error' && <div className="status-banner status-banner--error">{shares.message}</div>}
      {shares.kind === 'loaded' && shares.shares.length === 0 && (
        <div className="status-banner">아직 게시한 공유가 없습니다.</div>
      )}
      {shares.kind === 'loaded' && shares.shares.length > 0 && (
        <ul className="source-list">
          {shares.shares.map((share) => (
            <ShareRow
              key={share.id}
              share={share}
              fileLabel={describeFileLabel(share, picker)}
              unsharing={unsharingId === share.id}
              error={shareRowErrors[share.id]}
              onEdit={() => setDialog({ mode: 'edit', share })}
              onUnshare={() => handleUnshare(share)}
            />
          ))}
        </ul>
      )}

      {dialog?.mode === 'create' && (
        <ShareSettingsDialog
          mode="create"
          files={dialog.files}
          onClose={() => {
            setDialog(null)
            clearSelection()
            fetchShares()
          }}
        />
      )}
      {dialog?.mode === 'edit' && (
        <ShareSettingsDialog
          mode="edit"
          share={dialog.share}
          fileLabel={describeFileLabel(dialog.share, picker)}
          onClose={() => {
            setDialog(null)
            fetchShares()
          }}
        />
      )}
    </>
  )
}

function ShareIdCard({ subject }: { subject: string | null }) {
  const [copied, setCopied] = useState(false)
  if (!subject) {
    return null
  }
  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(subject as string)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
    }
  }
  return (
    <div className="card">
      <p className="form-label" style={{ marginBottom: 4 }}>
        내 SDV 공유 ID
      </p>
      <p className="text-secondary" style={{ marginTop: 0 }}>
        이메일이나 Google 계정이 아닙니다 - 다른 사람이 나에게 파일을 공유하려면 이 값이 필요합니다. 이 값을 찾아줄
        디렉터리 검색 기능은 없으니, 상대방에게 직접 전달해 주세요.
      </p>
      <div className="form-row">
        <code className="input" style={{ userSelect: 'all', overflowWrap: 'anywhere' }}>
          {subject}
        </code>
        <button type="button" className="btn" onClick={handleCopy}>
          {copied ? '복사됨' : '복사'}
        </button>
      </div>
    </div>
  )
}

interface ConnectionRowProps {
  source: SourceResponse
  connecting: boolean
  disconnecting: boolean
  confirming: boolean
  syncing: boolean
  syncOutcome?: SyncOutcome
  error?: string
  browsing: boolean
  onConnect: () => void
  onAskDisconnect: () => void
  onCancelDisconnect: () => void
  onConfirmDisconnect: () => void
  onSync: () => void
  onBrowse: () => void
}

function ConnectionRow({
  source,
  connecting,
  disconnecting,
  confirming,
  syncing,
  syncOutcome,
  error,
  browsing,
  onConnect,
  onAskDisconnect,
  onCancelDisconnect,
  onConfirmDisconnect,
  onSync,
  onBrowse,
}: ConnectionRowProps) {
  const isActive = source.status === 'ACTIVE'
  // M16C 후속 교정 - "DISABLED"는 Backend가 실제로 재연결을 지원하는 알려진
  // 상태다(`SourceConnectionService.disconnect`/`GoogleDriveOAuthService`
  // 재연결 흐름, M10B). 그 밖의 알 수 없는 문자열 상태는 재연결 가능하다고
  // 함부로 가정하지 않는다("Unknown statuses must not be assumed
  // reconnectable").
  const isKnownDisabled = source.status === 'DISABLED'
  const connectDisconnectBusy = connecting || disconnecting || confirming

  return (
    <li className="source-row">
      <div className="source-row__meta">
        <span className="source-row__name">{source.name}</span>
        <StatusPill source={source} />
        {isActive && source.credentialPresent && (
          <span className="text-secondary">
            연결됨 표시는 저장된 자격증명이 있다는 뜻일 뿐, 지금 이 순간 Google에서 실제로 쓸 수 있다는 증명은
            아닙니다 - 동기화나 공유 확인 시 다시 검증됩니다.
          </span>
        )}
        {error && <span className="status-banner status-banner--error">{error}</span>}
      </div>
      <div className="source-row__actions">
        {isKnownDisabled && (
          <>
            <span className="text-secondary">
              연결 해제된 상태입니다. 공유 설정은 그대로 보존되며, 처음 연결했던 것과 동일한 Google 계정으로
              재인증해야만 다시 연결됩니다(다른 계정이면 거부됩니다) - 재인증에 성공해도 각 파일이 실제로 다시
              사용 가능한지는 파일별로 다시 확인됩니다.
            </span>
            <button type="button" className="btn btn--primary" onClick={onConnect} disabled={connecting}>
              {connecting ? '연결 중...' : 'Google 재연결'}
            </button>
          </>
        )}
        {!isActive && !isKnownDisabled && (
          <span className="text-secondary">알 수 없는 연결 상태입니다 - 새로고침 후 다시 확인해 주세요.</span>
        )}
        {isActive && confirming && (
          <>
            <span>정말 연결을 해제할까요? 공유 설정은 그대로 보존됩니다.</span>
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
            {source.credentialPresent && (
              <button type="button" className="btn" onClick={onBrowse} disabled={connectDisconnectBusy}>
                {browsing ? '탐색 중' : '파일 보기'}
              </button>
            )}
            <button type="button" className="btn btn--danger" onClick={onAskDisconnect} disabled={disconnecting || syncing}>
              연결 해제
            </button>
          </>
        )}
      </div>
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
    return <span className="pill pill--connected">Google 계정 연결됨</span>
  }
  return <span className="pill pill--pending">Google 계정 연결 필요</span>
}

function FilePicker({
  source,
  picker,
  page,
  selected,
  onToggle,
  onPrevious,
  onNext,
  onClose,
}: {
  source: SourceResponse
  picker: PickerState
  page: number
  selected: Map<number, ShareTarget>
  onToggle: (file: SourceFileResponse) => void
  onPrevious: () => void
  onNext: () => void
  onClose: () => void
}) {
  return (
    <div className="card">
      <div className="form-row" style={{ justifyContent: 'space-between', alignItems: 'center', marginTop: 0 }}>
        <h2 style={{ margin: 0 }}>{source.name} - 파일 선택</h2>
        <button type="button" className="btn" onClick={onClose}>
          닫기
        </button>
      </div>
      <p className="text-secondary">
        폴더 구조 없이 평면 목록으로 보여줍니다(이번 화면의 알려진 한계 - 전체 Drive 크롤링/폴더 탐색은 아직
        구현하지 않았습니다). 여기서 체크만 해서는 아무것도 공유되지 않습니다.
      </p>

      {picker.kind === 'idle' || picker.kind === 'loading' ? (
        <div className="status-banner">파일 목록을 불러오는 중입니다...</div>
      ) : picker.kind === 'error' ? (
        <div className="status-banner status-banner--error">{picker.message}</div>
      ) : picker.items.length === 0 ? (
        <div className="status-banner">이 연결에 표시할 파일이 없습니다.</div>
      ) : (
        <ul className="file-list">
          {picker.items.map((file) => (
            <li key={file.documentId} className="file-row">
              <label className="checkbox-row file-row__meta">
                <input
                  type="checkbox"
                  checked={selected.has(file.documentId)}
                  onChange={() => onToggle(file)}
                  aria-label={`${file.name} 선택`}
                />
                <span className="file-row__name">{file.name}</span>
                <span className="text-secondary">{file.mimeType}</span>
              </label>
            </li>
          ))}
        </ul>
      )}

      <div className="form-row">
        <button type="button" className="btn" onClick={onPrevious} disabled={page === 0}>
          이전
        </button>
        <button
          type="button"
          className="btn"
          onClick={onNext}
          disabled={picker.kind !== 'loaded' || !picker.hasMore}
        >
          다음
        </button>
      </div>
    </div>
  )
}

function ShareRow({
  share,
  fileLabel,
  unsharing,
  error,
  onEdit,
  onUnshare,
}: {
  share: ShareResponse
  fileLabel: string
  unsharing: boolean
  error?: string
  onEdit: () => void
  onUnshare: () => void
}) {
  return (
    <li className="source-row">
      <div className="source-row__meta">
        <span className="source-row__name">{fileLabel}</span>
        <span className="text-secondary">
          등급 {share.classification} · 행위 {share.allowedActions.join(', ')} · 수신자 {share.recipients.length}명
        </span>
        {!share.active && <span className="pill pill--disabled">철회됨</span>}
        {share.active && share.adminBlocked && (
          <span className="status-banner status-banner--error">
            관리자 차단됨{share.adminBlockReason ? ` (${share.adminBlockReason})` : ''} - 게시자는 직접 해제할 수
            없습니다.
          </span>
        )}
        {error && <span className="status-banner status-banner--error">{error}</span>}
      </div>
      {share.active && (
        <div className="source-row__actions">
          <button type="button" className="btn" onClick={onEdit}>
            수정
          </button>
          <button type="button" className="btn btn--danger" onClick={onUnshare} disabled={unsharing}>
            {unsharing ? '해제 중...' : '공유 해제'}
          </button>
        </div>
      )}
    </li>
  )
}

/** 지금 열려 있는(같은 Source의) 선택기에 이 문서가 로드돼 있으면 실제 파일명을 보여준다 - 없으면 정직한 식별자로 대체한다(이름을 지어내지 않는다). */
function describeFileLabel(share: ShareResponse, picker: PickerState): string {
  if (picker.kind === 'loaded') {
    const match = picker.items.find((item) => item.documentId === share.documentId)
    if (match) {
      return match.name
    }
  }
  return `문서 #${share.documentId} (연결 #${share.sourceId})`
}

function toCallbackHint(flag: string | null): CallbackHint {
  return flag === 'success' || flag === 'failed' ? flag : null
}

function describeAuthorizationReturn(
  context: AuthorizationRecoveryContext,
  sources: SourceResponse[],
): AuthorizationReturnState {
  if (context.hint === 'failed') {
    return {
      kind: 'failed',
      message: 'Google 연결에 실패했거나 취소되었습니다. 기존 연결 정보는 변경하지 않았습니다. 다시 시도할 수 있습니다.',
    }
  }

  if (context.attempt !== null) {
    const current = sources.find((source) => source.id === context.attempt?.sourceId)
    if (
      !context.attempt.credentialPresentBefore &&
      current?.status === 'ACTIVE' &&
      current.credentialPresent
    ) {
      return {
        kind: 'confirmed',
        message: 'Google 연결 정보가 SDV에 저장되었습니다. 실제 Google 접근 가능 여부는 동기화할 때 다시 확인합니다.',
      }
    }
    return {
      kind: 'incomplete',
      message:
        'Google 연결 완료를 확인하지 못했습니다. 취소, Google 거부 또는 다른 오류였는지는 SDV가 구분할 수 없습니다. 기존 연결 정보는 그대로 유지되며 다시 시도할 수 있습니다.',
    }
  }

  const hasStoredConnection = sources.some(
    (source) => source.status === 'ACTIVE' && source.credentialPresent,
  )
  if (context.hint === 'success' && hasStoredConnection) {
    return {
      kind: 'incomplete',
      message:
        '현재 SDV에 저장된 Google 연결 정보를 확인했습니다. 주소의 완료 표시는 이번 요청이 새로 성공했다는 증거가 아니며, 실제 접근은 동기화할 때 다시 확인합니다.',
    }
  }
  return {
    kind: 'incomplete',
    message:
      'Google 연결 완료를 확인하지 못했습니다. 주소의 완료 표시는 연결 증거가 아닙니다. 아래 상태를 확인하고 다시 시도할 수 있습니다.',
  }
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function describeSyncError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'AUTHENTICATION_REQUIRED':
        return '로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.'
      case 'ACCESS_DENIED':
        return '이 작업을 수행할 권한이 없습니다.'
      case 'NOT_FOUND':
        return '해당 연결을 찾을 수 없습니다.'
      case 'SYNC_ALREADY_RUNNING':
        return '이미 이 연결에 대한 동기화가 진행 중입니다. 잠시 후 다시 시도해 주세요.'
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

function describeSourceError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'AUTHENTICATION_REQUIRED':
        return '로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.'
      case 'ACCESS_DENIED':
        return '이 작업을 수행할 권한이 없습니다.'
      case 'NOT_FOUND':
        return '해당 연결을 찾을 수 없습니다.'
      case 'VALIDATION_ERROR':
        return '입력값을 확인해 주세요.'
      case 'OAUTH_UNAVAILABLE':
        return 'Google 연결 기능을 현재 사용할 수 없습니다. SDV 운영자에게 문의해 주세요.'
      case 'NETWORK_ERROR':
        return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
      default:
        return '알 수 없는 오류가 발생했습니다.'
    }
  }
  return '알 수 없는 오류가 발생했습니다.'
}

function describeShareListError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'AUTHENTICATION_REQUIRED':
        return '로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.'
      case 'NOT_FOUND':
        return '해당 공유를 찾을 수 없습니다 - 이미 철회됐을 수 있습니다.'
      case 'NETWORK_ERROR':
        return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
      default:
        return '공유 목록을 처리하지 못했습니다.'
    }
  }
  return '공유 목록을 처리하지 못했습니다.'
}
