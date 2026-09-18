import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { useApiClient } from '../../api/useApiClient'
import { ApiError } from '../../api/client'
import {
  CLASSIFICATION_OPTIONS,
  MAX_RECIPIENTS,
  SHARE_ACTION_OPTIONS,
  createShare,
  listMyShares,
  updateShare,
} from '../../api/shares'
import type { Classification, ShareAction, ShareResponse } from '../../api/shares'
import type { ShareAudience } from '../../api/shares'
import { searchDirectory } from '../../api/users'
import type { DirectoryUser } from '../../api/users'

/** One file this dialog can publish - identity only, never a filename guessed/derived from anything else. */
export interface ShareTarget {
  sourceId: number
  documentId: number
  name: string
}

type PerFileStatus =
  | { kind: 'pending' }
  | { kind: 'success' }
  | { kind: 'error'; message: string }
  /** The request's own outcome is unknown (e.g. a network failure) - it may or may not have reached the server. */
  | { kind: 'uncertain'; message: string }

type ShareSettingsDialogProps =
  | {
      mode: 'create'
      files: ShareTarget[]
      /** Called both for the explicit "닫기" click and (after create) implicitly never - the caller should re-fetch its own share list whenever this fires, since some files may have already succeeded. */
      onClose: () => void
    }
  | {
      mode: 'edit'
      share: ShareResponse
      fileLabel: string
      /** Called on explicit "닫기" AND automatically right after a successful save - either way, the caller should treat it as "safe to re-fetch and dismiss". */
      onClose: () => void
    }

const CLASSIFICATION_LABELS: Record<Classification, string> = {
  PUBLIC: '공개(PUBLIC)',
  INTERNAL: '내부(INTERNAL)',
  CONFIDENTIAL: '기밀(CONFIDENTIAL)',
  SECRET: '대외비(SECRET)',
}

const ACTION_LABELS: Record<ShareAction, string> = {
  VIEW: '찾기(VIEW) - 공통 검색에서 이 파일을 찾을 수 있게 합니다',
  DOWNLOAD: '다운로드(DOWNLOAD) - SDV를 통한 다운로드를 허용합니다',
}

/**
 * 공유 생성(여러 파일 한 번에)과 공유 수정(기존 공유 하나)을 함께 다루는
 * 대화상자. 기본 audience는 ALL_AUTHENTICATED지만 익명/인터넷 공개가 아니다.
 * 로그인한 active SDV 사용자도 ADMIN이 지정한 현재 최대 열람 등급과 행위,
 * Overlay, 게시자 원본 권한을 모두 통과해야 한다. NAMED_USERS는 서버가 찾은
 * 안정 identity를 선택해 이 범위를 더 좁힐 때만 사용한다.
 *
 * <h2>여러 파일 = 여러 개의 독립된 요청</h2>
 * <p>"생성" 모드에서 파일을 여러 개 고르면, 이 대화상자는 그 파일 수만큼
 * {@link createShare} 호출을 하나씩 순서대로 보낸다 - 하나의 원자적(All-or-
 * Nothing) Bulk 요청이 아니다. 각 파일의 성공/실패를 각자 보여주고, 실패한
 * 파일만 다시 시도할 수 있다. 이미 성공한 파일은 재시도로 다시 건드리지
 * 않는다(자동으로 되돌리지도 않는다). Network 오류처럼 "실제로 서버에
 * 도착했는지 알 수 없는" 경우는 재시도 전에 먼저 지금 내 공유 목록을 다시
 * 조회해 이미 성공했는지부터 확인한다(추측하지 않는다).</p>
 */
export function ShareSettingsDialog(props: ShareSettingsDialogProps) {
  const { subject } = useAuth()
  const apiClient = useApiClient()
  const dialogRef = useRef<HTMLDivElement>(null)
  const firstFieldRef = useRef<HTMLSelectElement>(null)

  const initialShare = props.mode === 'edit' ? props.share : null
  const [audience, setAudience] = useState<ShareAudience>(initialShare?.audience ?? 'ALL_AUTHENTICATED')
  const [classification, setClassification] = useState<Classification>(initialShare?.classification ?? 'INTERNAL')
  const [actions, setActions] = useState<Set<ShareAction>>(
    new Set<ShareAction>(initialShare?.allowedActions ?? ['VIEW']),
  )
  const [selectedRecipients, setSelectedRecipients] = useState<DirectoryUser[]>(
    initialShare?.recipients.filter((recipient): recipient is DirectoryUser => recipient.id !== null) ?? [],
  )
  const [recipientQuery, setRecipientQuery] = useState('')
  const [recipientCandidates, setRecipientCandidates] = useState<DirectoryUser[]>([])
  const [directoryState, setDirectoryState] = useState<'idle' | 'loading' | 'error'>('idle')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // "생성" 모드 전용 - 파일별 진행 상태.
  const [results, setResults] = useState<Record<number, PerFileStatus>>({})

  // "수정" 모드 전용.
  const [editShare, setEditShare] = useState<ShareResponse | null>(initialShare)
  const [editNotice, setEditNotice] = useState<string | null>(null)
  const [editError, setEditError] = useState<string | null>(null)

  // ---------------- M16C 후속 교정 - Dialog/Session 생명주기 ----------------
  // `submitting`(React State)만으로는 두 가지가 부족하다: (1) Escape Handler를
  // 등록하는 Effect가 Mount 시 한 번만 실행되므로 그 Closure가 항상 최초
  // `submitting`(false) 값만 본다, (2) React State 갱신은 비동기/Batch라
  // 거의 동시에 두 번 제출하면 둘 다 "아직 false"를 볼 수 있는 경쟁이 남는다.
  // 아래 Ref들은 이 두 문제를 Ref의 동기적/항상-최신 특성으로 닫는다.
  const submittingRef = useRef(false)
  useEffect(() => {
    submittingRef.current = submitting
  }, [submitting])

  // M16C 후속 교정(2차) - 지금 실행 중인 HTTP Mutation을 실제로 취소하는
  // AbortController. `isOperationStillValid`(아래)는 File 사이/await 이후의
  // "다음에 뭘 할지" 판단만 막을 뿐, 이미 시작된 낱개 요청 내부에서 Token
  // 조달(`getAccessToken`, 그 자체가 비동기)이 끝나고 실제 `fetch`가 나가기
  // 직전까지의 틈은 막지 못했다 - `client.ts`의 `authorizedFetch`가 바로 그
  // 틈에서 이 Signal을 다시 확인하므로, 여기서 제때 `abort()`만 호출해 주면
  // Token 조달이 끝난 뒤에도 Network에 전혀 나가지 않는다.
  const activeControllerRef = useRef<AbortController | null>(null)

  // Unmount(Dialog가 닫히거나, 상위 MyDrivePage 자체가 계정 전환으로
  // `key={subject}` 재마운트되거나, 로그아웃으로 전체 트리가 사라지는 경우
  // 모두 포함) 이후에는 남은 Batch를 더 이상 진행하지 않고, 이미 진행 중이던
  // 개별 요청의 응답이 뒤늦게 와도 State를 건드리지 않는다 - 지금 실행 중인
  // Mutation이 있다면 그 자리에서 즉시 Abort한다.
  const isMountedRef = useRef(true)
  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
      activeControllerRef.current?.abort()
    }
  }, [])

  // "계정 전환"을 Unmount와 별개로도 직접 감지한다(방어적 이중 장치 - 이
  // Dialog가 앞으로 다른 방식으로 재사용돼 더 이상 Key로 재마운트되지 않게
  // 되더라도 여전히 안전하다). 매 Batch 시작 시점의 `subject`를 캡처해 두고,
  // 그 뒤 매 Dispatch/매 await 이후 지금의(항상 최신인) `sessionSubjectRef`와
  // 비교한다 - 다르면 로그아웃(subject=null)이든 다른 계정으로 전환됐든,
  // "다른 계정의 Token으로 이전 작업을 계속하지 않는다." 실제로 값이 바뀐
  // 경우에만(Mount 시점의 최초 동기화는 제외) 지금 실행 중인 Mutation을
  // Abort한다.
  const sessionSubjectRef = useRef(subject)
  useEffect(() => {
    const changed = sessionSubjectRef.current !== subject
    sessionSubjectRef.current = subject
    if (changed) {
      activeControllerRef.current?.abort()
    }
  }, [subject])

  /** Unmount되지 않았고, Batch 시작 시점과 지금의 계정이 같을 때만 계속 진행해도 된다. */
  function isOperationStillValid(expectedSubject: string | null): boolean {
    return isMountedRef.current && sessionSubjectRef.current === expectedSubject
  }

  /** 취소(Abort)는 조용히 처리한다 - 실패로 표시하지도, 자동으로 재시도하지도 않는다. */
  function isCancellation(error: unknown): boolean {
    return error instanceof DOMException && error.name === 'AbortError'
  }

  // 여러 File(Submit/재시도)이 겹쳐 두 Batch가 동시에 나가지 않도록 하는
  // 동기(Synchronous) 진입 Guard - `submitting` State는 다음 Render까지
  // 반영이 늦을 수 있어 그 사이의 두 번째 클릭을 막지 못할 수 있다.
  const inFlightRef = useRef(false)

  useEffect(() => {
    firstFieldRef.current?.focus()
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        // 제출 중에는 "닫기" 버튼도 disabled인 것과 똑같이 취급한다 - Escape가
        // 그 Guard를 우회하는 뒷문이 되지 않게 한다.
        if (submittingRef.current) {
          return
        }
        props.onClose()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- Mount 시 한 번만 등록한다(onClose 참조 변경으로 다시 등록할 필요 없음, submittingRef는 항상 최신값을 읽는다).
  }, [])

  useEffect(() => {
    if (audience !== 'NAMED_USERS' || recipientQuery.trim().length < 2) {
      setRecipientCandidates([])
      setDirectoryState('idle')
      return
    }
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      setDirectoryState('loading')
      searchDirectory(apiClient, recipientQuery.trim(), controller.signal)
        .then((items) => {
          setRecipientCandidates(
            items.filter((item) => !selectedRecipients.some((selected) => selected.id === item.id)),
          )
          setDirectoryState('idle')
        })
        .catch((error: unknown) => {
          if (error instanceof DOMException && error.name === 'AbortError') return
          setRecipientCandidates([])
          setDirectoryState('error')
        })
    }, 250)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [apiClient, audience, recipientQuery, selectedRecipients])

  function selectedRecipientIds(): number[] | null {
    if (audience === 'ALL_AUTHENTICATED') return []
    if (selectedRecipients.length === 0) {
      setValidationError('특정 사용자 모드에서는 검색 결과에서 한 명 이상 선택해 주세요.')
      return null
    }
    if (selectedRecipients.length > MAX_RECIPIENTS) {
      setValidationError(`수신자는 최대 ${MAX_RECIPIENTS}명까지 지정할 수 있습니다.`)
      return null
    }
    return selectedRecipients.map((recipient) => recipient.id)
  }

  function toggleAction(action: ShareAction) {
    setActions((prev) => {
      const next = new Set(prev)
      if (next.has(action)) {
        next.delete(action)
      } else {
        next.add(action)
      }
      return next
    })
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (inFlightRef.current) {
      return
    }
    setValidationError(null)
    const recipients = selectedRecipientIds()
    if (!recipients) {
      return
    }
    if (actions.size === 0) {
      setValidationError('행위를 하나 이상 선택해 주세요.')
      return
    }

    inFlightRef.current = true
    setSubmitting(true)
    const controller = new AbortController()
    activeControllerRef.current = controller
    try {
      if (props.mode === 'create') {
        await runCreate(props.files, recipients, subject, controller.signal)
      } else {
        await runUpdate(recipients, subject, controller.signal)
      }
    } finally {
      inFlightRef.current = false
      if (activeControllerRef.current === controller) {
        activeControllerRef.current = null
      }
      if (isMountedRef.current) {
        setSubmitting(false)
      }
    }
  }

  async function runCreate(
    files: ShareTarget[],
    recipients: number[],
    expectedSubject: string | null,
    signal: AbortSignal,
  ) {
    const pending: Record<number, PerFileStatus> = {}
    for (const file of files) {
      pending[file.documentId] = { kind: 'pending' }
    }
    setResults(pending)
    for (const file of files) {
      // 다음 File을 실제로 내보내기(Dispatch) 직전에 매번 다시 확인한다 - 이미
      // 나간 요청은 취소할 수 없지만("Cancellation cannot promise rollback of
      // a request already accepted by the server"), 아직 안 나간 나머지는
      // 여기서 멈춘다.
      if (!isOperationStillValid(expectedSubject)) {
        return
      }
      await attemptCreate(file, recipients, expectedSubject, signal)
    }
    // 의도적으로 여기서 자동으로 닫지 않는다 - 일부만 성공했을 수 있으므로,
    // 사용자가 결과를 직접 확인하고(필요하면 실패한 항목만 재시도한 뒤)
    // "닫기"를 눌러야 한다("Do not claim atomic bulk success").
  }

  async function attemptCreate(
    file: ShareTarget,
    recipients: number[],
    expectedSubject: string | null,
    signal: AbortSignal,
  ) {
    try {
      await createShare(
        apiClient,
        {
          sourceId: file.sourceId,
          documentId: file.documentId,
          classification,
          actions: Array.from(actions),
          audience,
          recipientUserIds: recipients,
        },
        signal,
      )
      // Await 이후(Token 갱신이 끼어들 수 있는 지점 포함) - 이미 Unmount됐거나
      // 다른 계정으로 바뀌었으면 이 결과를 화면에 반영하지 않는다("Suppress
      // obsolete state updates"). 서버에는 이미 반영된 성공이므로 되돌리지
      // 않는다 - 여기서 하는 일은 오직 "낡은 화면에 표시하지 않기"뿐이다.
      if (!isOperationStillValid(expectedSubject)) {
        return
      }
      setResults((prev) => ({ ...prev, [file.documentId]: { kind: 'success' } }))
    } catch (error) {
      if (isCancellation(error)) {
        return // 조용히 무시 - 실패로 표시하지도, 자동 재시도하지도 않는다.
      }
      if (!isOperationStillValid(expectedSubject)) {
        return
      }
      setResults((prev) => ({ ...prev, [file.documentId]: classifyFailure(error) }))
    }
  }

  async function retryFailedFiles() {
    if (props.mode !== 'create' || inFlightRef.current) {
      return
    }
    const recipients = selectedRecipientIds()
    if (!recipients) {
      return
    }
    const expectedSubject = subject
    inFlightRef.current = true
    setSubmitting(true)
    const controller = new AbortController()
    activeControllerRef.current = controller
    try {
      const toRetry = props.files.filter((file) => {
        const status = results[file.documentId]
        return status?.kind === 'error' || status?.kind === 'uncertain'
      })
      for (const file of toRetry) {
        if (!isOperationStillValid(expectedSubject)) {
          return
        }
        const status = results[file.documentId]
        if (status?.kind === 'uncertain') {
          // 결과가 불확실했다(예: Network 오류) - 그대로 다시 시도하기 전에, 실제로
          // 이미 성공했는지부터 확인한다(추측/무조건 재시도 금지). 이 파일에는
          // createShare가 "문서당 활성 공유는 하나뿐"을 강제하므로, 지금 활성
          // 공유가 있다면 그것이 바로 방금 시도의 결과라고 봐도 안전하다.
          try {
            const mine = await listMyShares(apiClient, controller.signal)
            if (!isOperationStillValid(expectedSubject)) {
              return
            }
            const already = mine.find(
              (share) => share.sourceId === file.sourceId && share.documentId === file.documentId && share.active,
            )
            if (already) {
              setResults((prev) => ({ ...prev, [file.documentId]: { kind: 'success' } }))
              continue
            }
          } catch (error) {
            if (isCancellation(error)) {
              return
            }
            // 확인 자체가 실패했다 - 이번 재시도 회차는 건너뛰고 그대로 불확실 상태를 유지한다.
            continue
          }
        }
        await attemptCreate(file, recipients, expectedSubject, controller.signal)
      }
    } finally {
      inFlightRef.current = false
      if (activeControllerRef.current === controller) {
        activeControllerRef.current = null
      }
      if (isMountedRef.current) {
        setSubmitting(false)
      }
    }
  }

  async function runUpdate(recipients: number[], expectedSubject: string | null, signal: AbortSignal) {
    if (props.mode !== 'edit' || !editShare) {
      return
    }
    setEditError(null)
    try {
      await updateShare(
        apiClient,
        editShare.id,
        {
          expectedGeneration: editShare.generation,
          audience,
          classification,
          actions: Array.from(actions),
          recipientUserIds: recipients,
        },
        signal,
      )
      if (!isOperationStillValid(expectedSubject)) {
        return
      }
      props.onClose()
    } catch (error) {
      if (isCancellation(error)) {
        return
      }
      if (!isOperationStillValid(expectedSubject)) {
        return
      }
      if (error instanceof ApiError && error.code === 'SHARE_GENERATION_CONFLICT') {
        await reloadAfterConflict(expectedSubject, signal)
        return
      }
      setEditError(describeShareError(error))
    }
  }

  async function reloadAfterConflict(expectedSubject: string | null, signal: AbortSignal) {
    setEditNotice('다른 곳에서 이미 바뀐 내용이 있어 최신 내용을 다시 불러왔습니다. 확인한 뒤 다시 저장해 주세요.')
    try {
      const mine = await listMyShares(apiClient, signal)
      if (!isOperationStillValid(expectedSubject)) {
        return
      }
      const fresh = mine.find((share) => editShare && share.id === editShare.id)
      if (!fresh) {
        setEditError('이 공유를 더 이상 찾을 수 없습니다 - 이미 철회됐을 수 있습니다.')
        return
      }
      setEditShare(fresh)
      setClassification(fresh.classification)
      setAudience(fresh.audience)
      setActions(new Set(fresh.allowedActions))
      setSelectedRecipients(fresh.recipients.filter((recipient): recipient is DirectoryUser => recipient.id !== null))
    } catch (error) {
      if (isCancellation(error)) {
        return
      }
      if (!isOperationStillValid(expectedSubject)) {
        return
      }
      setEditError('최신 내용을 다시 불러오지 못했습니다 - 다시 시도해 주세요.')
    }
  }

  const title = props.mode === 'create' ? '선택한 파일 공유하기' : '공유 설정 수정'

  return (
    <div className="dialog-overlay" role="presentation">
      <div ref={dialogRef} className="dialog card" role="dialog" aria-modal="true" aria-labelledby="share-dialog-title">
        <h2 id="share-dialog-title" style={{ marginTop: 0 }}>
          {title}
        </h2>

        {props.mode === 'create' && (
          <p className="text-secondary">
            선택한 파일 {props.files.length}개에 아래와 같이 공유합니다. 파일마다 독립적으로 처리되며, 일부만 성공할 수
            있습니다.
          </p>
        )}
        {props.mode === 'edit' && <p className="text-secondary">대상 파일: {props.fileLabel}</p>}
        {props.mode === 'edit' && editShare?.adminBlocked && (
          <div className="status-banner status-banner--error">
            관리자가 이 공유를 차단했습니다{editShare.adminBlockReason ? ` (사유: ${editShare.adminBlockReason})` : ''}.
            게시자는 이 차단을 직접 해제할 수 없습니다 - 아래에서 등급/행위/수신자를 바꿔도 차단 자체는 풀리지 않습니다.
          </div>
        )}
        {editNotice && <div className="status-banner">{editNotice}</div>}

        <form onSubmit={handleSubmit}>
          <fieldset className="share-audience" disabled={submitting}>
            <legend className="form-label">공유 대상</legend>
            <label>
              <input
                type="radio"
                name="share-audience"
                value="ALL_AUTHENTICATED"
                checked={audience === 'ALL_AUTHENTICATED'}
                onChange={() => setAudience('ALL_AUTHENTICATED')}
              />{' '}
              파일 등급을 읽을 수 있는 모든 SDV 사용자
            </label>
            <label>
              <input
                type="radio"
                name="share-audience"
                value="NAMED_USERS"
                checked={audience === 'NAMED_USERS'}
                onChange={() => setAudience('NAMED_USERS')}
              />{' '}
              특정 사용자만
            </label>
          </fieldset>
          <p className="text-secondary">
            모든 대상도 로그인과 현재 보안 등급, 허용 행위, 게시자의 원본 권한을 모두 통과해야 합니다.
          </p>
          <label className="form-label" htmlFor="share-classification">
            보안 등급
          </label>
          <div className="form-row">
            <select
              id="share-classification"
              ref={firstFieldRef}
              className="input"
              value={classification}
              onChange={(event) => setClassification(event.target.value as Classification)}
              disabled={submitting}
            >
              {CLASSIFICATION_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {CLASSIFICATION_LABELS[option]}
                </option>
              ))}
            </select>
          </div>

          <span className="form-label">허용할 행위</span>
          <div className="form-row" style={{ flexDirection: 'column', alignItems: 'flex-start' }}>
            {SHARE_ACTION_OPTIONS.map((option) => (
              <label key={option} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <input
                  type="checkbox"
                  checked={actions.has(option)}
                  onChange={() => toggleAction(option)}
                  disabled={submitting}
                />
                {ACTION_LABELS[option]}
              </label>
            ))}
          </div>

          {audience === 'NAMED_USERS' && (
            <div className="recipient-picker">
              <label className="form-label" htmlFor="share-recipient-search">
                사용자 찾기 (로그인 ID, 최대 {MAX_RECIPIENTS}명)
              </label>
              <input
                id="share-recipient-search"
                className="input"
                value={recipientQuery}
                onChange={(event) => setRecipientQuery(event.target.value)}
                placeholder="예: sdv-user-b"
                autoComplete="off"
                disabled={submitting}
                aria-controls="share-recipient-options"
              />
              <div className="recipient-chips" aria-label="선택한 사용자">
                {selectedRecipients.map((recipient) => (
                  <span className="recipient-chip" key={recipient.id}>
                    {recipient.loginId}
                    <button
                      type="button"
                      aria-label={`${recipient.loginId} 선택 제거`}
                      onClick={() =>
                        setSelectedRecipients((current) => current.filter((item) => item.id !== recipient.id))
                      }
                      disabled={submitting}
                    >
                      ×
                    </button>
                  </span>
                ))}
              </div>
              {directoryState === 'loading' && <div className="text-secondary">사용자를 찾는 중...</div>}
              {directoryState === 'error' && (
                <div className="status-banner status-banner--error">
                  사용자 검색에 실패했습니다. 다시 입력해 주세요.
                </div>
              )}
              {recipientQuery.trim().length >= 2 && directoryState === 'idle' && (
                <ul id="share-recipient-options" className="recipient-options" role="listbox">
                  {recipientCandidates.map((candidate) => (
                    <li key={candidate.id}>
                      <button
                        type="button"
                        role="option"
                        className="recipient-option"
                        onClick={() => {
                          setSelectedRecipients((current) => [...current, candidate])
                          setRecipientQuery('')
                        }}
                      >
                        <strong>{candidate.loginId}</strong>
                        {candidate.displayName && <span>{candidate.displayName}</span>}
                      </button>
                    </li>
                  ))}
                  {recipientCandidates.length === 0 && <li className="text-secondary">일치하는 사용자가 없습니다.</li>}
                </ul>
              )}
            </div>
          )}

          {validationError && <div className="status-banner status-banner--error form-row">{validationError}</div>}
          {props.mode === 'edit' && editError && (
            <div className="status-banner status-banner--error form-row">{editError}</div>
          )}

          <div className="form-row">
            <button type="button" className="btn" onClick={props.onClose} disabled={submitting}>
              닫기
            </button>
            {/* 생성 모드에서 한 번 결과가 나온 뒤에는 이 Submit으로 전체를 다시
                보내지 않는다 - 이미 성공한 파일까지 다시 시도해 "중복 활성
                공유" 오류로 잘못 되돌아 보이게 만들 수 있다. 그 이후로는
                아래 "실패한 항목만 다시 시도"만 쓴다. */}
            {(props.mode === 'edit' || Object.keys(results).length === 0) && (
              <button type="submit" className="btn btn--primary" disabled={submitting}>
                {submitting ? '처리 중...' : props.mode === 'create' ? '공유하기' : '저장'}
              </button>
            )}
          </div>
        </form>

        {props.mode === 'create' && Object.keys(results).length > 0 && (
          <div className="form-row" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
            <h3 style={{ marginBottom: 4 }}>진행 결과</h3>
            <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 6 }}>
              {props.files.map((file) => (
                <li key={file.documentId}>
                  <ResultRow file={file} status={results[file.documentId]} />
                </li>
              ))}
            </ul>
            {Object.values(results).some((status) => status.kind === 'error' || status.kind === 'uncertain') && (
              <button type="button" className="btn" onClick={retryFailedFiles} disabled={submitting}>
                실패한 항목만 다시 시도
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

function ResultRow({ file, status }: { file: ShareTarget; status?: PerFileStatus }) {
  if (!status || status.kind === 'pending') {
    return <span className="text-secondary">{file.name} - 처리 중...</span>
  }
  if (status.kind === 'success') {
    return <span className="text-success">{file.name} - 공유 완료</span>
  }
  return (
    <span className="text-danger">
      {file.name} - {status.message}
    </span>
  )
}

function classifyFailure(error: unknown): PerFileStatus {
  if (error instanceof ApiError) {
    if (error.code === 'NETWORK_ERROR') {
      return { kind: 'uncertain', message: '서버 응답을 확인하지 못했습니다 - 재시도 전 자동으로 다시 확인합니다.' }
    }
    return { kind: 'error', message: describeShareError(error) }
  }
  return { kind: 'error', message: '알 수 없는 오류가 발생했습니다.' }
}

function describeShareError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'AUTHENTICATION_REQUIRED':
        return '로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.'
      case 'ACCESS_DENIED':
        return '이 작업을 수행할 권한이 없습니다.'
      case 'NOT_FOUND':
        return '대상을 찾을 수 없습니다 - 이미 삭제/철회됐을 수 있습니다.'
      case 'VALIDATION_ERROR':
        return '입력값을 확인해 주세요(수신자 형식/등급/행위 등).'
      case 'SHARE_GENERATION_CONFLICT':
        return '다른 곳에서 이미 변경됐습니다 - 새로고침 후 다시 시도해 주세요.'
      case 'NETWORK_ERROR':
        return '서버에 연결할 수 없습니다 - 잠시 후 다시 시도해 주세요.'
      default:
        return '알 수 없는 오류가 발생했습니다.'
    }
  }
  return '알 수 없는 오류가 발생했습니다.'
}
