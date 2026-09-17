import { useEffect, useRef, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { useApiClient } from '../api/useApiClient'
import { listAdminShares, setShareBlocked } from '../api/shares'
import type { AdminShareResponse } from '../api/shares'
import { ApiError } from '../api/client'

type ListState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'loaded'; shares: AdminShareResponse[] }

/**
 * M16C 신규(SHR-006, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.13) - ADMIN 전용
 * "공유 자료 관리" 화면(`GET/PATCH /api/admin/shares`). {@link
 * SourcesPage}(연결 관리)와 완전히 다른 화면이다 - 이 화면은 이미 게시된
 * 공유의 정책/차단만 다루고, 다른 사람의 비공개 Drive를 열람하거나 수신자/
 * 등급/행위를 넓히는 어떤 방법도 제공하지 않는다(ADMIN Role 자체가 자동
 * 다운로드 권한도 아니다 - 이 화면은 Content를 어디에서도 Fetch하지 않는다).
 */
export function SharedMaterialsAdminPage() {
  const { isAdmin } = useAuth()

  if (!isAdmin) {
    return (
      <>
        <h1>공유 자료 관리</h1>
        <div className="status-banner">이 화면은 관리자만 사용할 수 있습니다.</div>
      </>
    )
  }

  return <AdminSharedMaterialsPage />
}

function AdminSharedMaterialsPage() {
  const apiClient = useApiClient()
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const [blockingId, setBlockingId] = useState<number | null>(null)
  const [reasonDraft, setReasonDraft] = useState<Record<number, string>>({})
  const [askingBlockId, setAskingBlockId] = useState<number | null>(null)
  const [rowErrors, setRowErrors] = useState<Record<number, string>>({})
  const fetchSeq = useRef(0)

  function fetchShares() {
    const requestId = ++fetchSeq.current
    listAdminShares(apiClient)
      .then((shares) => {
        if (fetchSeq.current !== requestId) return
        setState({ kind: 'loaded', shares })
      })
      .catch((error: unknown) => {
        if (fetchSeq.current !== requestId) return
        setState({ kind: 'error', message: describeAdminShareError(error) })
      })
  }

  useEffect(() => {
    fetchShares()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- apiClient만 실제로 바뀌는 의존성이다.
  }, [apiClient])

  function clearRowError(id: number) {
    setRowErrors((prev) => {
      if (!(id in prev)) return prev
      const next = { ...prev }
      delete next[id]
      return next
    })
  }

  async function handleBlock(share: AdminShareResponse) {
    if (blockingId !== null) return
    setBlockingId(share.id)
    clearRowError(share.id)
    try {
      await setShareBlocked(apiClient, share.id, true, reasonDraft[share.id]?.trim() || undefined)
      setAskingBlockId(null)
      refreshOneRow()
    } catch (error) {
      setRowErrors((prev) => ({ ...prev, [share.id]: describeAdminShareError(error) }))
    } finally {
      setBlockingId(null)
    }
  }

  async function handleUnblock(share: AdminShareResponse) {
    if (blockingId !== null) return
    setBlockingId(share.id)
    clearRowError(share.id)
    try {
      await setShareBlocked(apiClient, share.id, false)
      refreshOneRow()
    } catch (error) {
      setRowErrors((prev) => ({ ...prev, [share.id]: describeAdminShareError(error) }))
    } finally {
      setBlockingId(null)
    }
  }

  // 개별 행 갱신 대신 전체 목록을 다시 불러온다 - 이 화면 자체가 이미 가벼운
  // 관리 목록이고, "낡은/철회된 항목"을 정직하게 반영하는 가장 단순한 방법이다
  // (예: 그 사이 게시자가 unshare해 이 목록에서 아예 사라졌을 수도 있다).
  function refreshOneRow() {
    fetchShares()
  }

  if (state.kind === 'loading') {
    return (
      <>
        <h1>공유 자료 관리</h1>
        <div className="status-banner">공유 목록을 불러오는 중입니다...</div>
      </>
    )
  }

  if (state.kind === 'error') {
    return (
      <>
        <h1>공유 자료 관리</h1>
        <div className="status-banner status-banner--error">
          <p style={{ margin: 0 }}>공유 목록을 불러오지 못했습니다: {state.message}</p>
          <div className="form-row">
            <button type="button" className="btn" onClick={fetchShares}>
              다시 시도
            </button>
          </div>
        </div>
      </>
    )
  }

  return (
    <>
      <h1>공유 자료 관리</h1>
      <p className="text-secondary">
        게시된 공유의 정책/차단만 관리합니다. 수신자·등급·행위는 게시자만 바꿀 수 있고, 관리자는 이를 넓힐 수
        없습니다. 다른 사람의 비공개 Drive는 이 화면에서 볼 수 없습니다.
      </p>

      {state.shares.length === 0 && <div className="status-banner">현재 게시된 공유가 없습니다.</div>}

      {state.shares.length > 0 && (
        <ul className="source-list">
          {state.shares.map((share) => (
            <li key={share.id} className="source-row">
              <div className="source-row__meta">
                <span className="source-row__name">
                  문서 #{share.documentId} (연결 #{share.sourceId})
                </span>
                <span className="text-secondary">게시자: 인증된 SDV 사용자</span>
                <span className="text-secondary">
                  등급 {share.classification} · 행위 {share.allowedActions.join(', ')} · 수신자{' '}
                  {share.recipients.length}명
                </span>
                {share.adminBlocked ? (
                  <span className="pill pill--pending">차단됨{share.adminBlockReason ? ` - ${share.adminBlockReason}` : ''}</span>
                ) : (
                  <span className="pill pill--connected">정상 게시 중</span>
                )}
                {rowErrors[share.id] && (
                  <span className="status-banner status-banner--error">{rowErrors[share.id]}</span>
                )}
              </div>
              <div className="source-row__actions">
                {share.adminBlocked ? (
                  <button
                    type="button"
                    className="btn"
                    onClick={() => handleUnblock(share)}
                    disabled={blockingId === share.id}
                  >
                    {blockingId === share.id ? '해제 중...' : '차단 해제'}
                  </button>
                ) : askingBlockId === share.id ? (
                  <>
                    <input
                      className="input"
                      style={{ minWidth: 180 }}
                      placeholder="차단 사유(선택)"
                      value={reasonDraft[share.id] ?? ''}
                      onChange={(event) => setReasonDraft((prev) => ({ ...prev, [share.id]: event.target.value }))}
                    />
                    <button type="button" className="btn" onClick={() => setAskingBlockId(null)}>
                      취소
                    </button>
                    <button
                      type="button"
                      className="btn btn--danger"
                      onClick={() => handleBlock(share)}
                      disabled={blockingId === share.id}
                    >
                      {blockingId === share.id ? '차단 중...' : '차단 확인'}
                    </button>
                  </>
                ) : (
                  <button type="button" className="btn btn--danger" onClick={() => setAskingBlockId(share.id)}>
                    차단
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

function describeAdminShareError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'AUTHENTICATION_REQUIRED':
        return '로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.'
      case 'ACCESS_DENIED':
        return '이 작업을 수행할 권한이 없습니다.'
      case 'NOT_FOUND':
        return '이 공유를 더 이상 찾을 수 없습니다 - 이미 철회됐을 수 있습니다. 새로고침해 주세요.'
      case 'NETWORK_ERROR':
        return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
      default:
        return '알 수 없는 오류가 발생했습니다.'
    }
  }
  return '알 수 없는 오류가 발생했습니다.'
}
