import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { useApiClient } from '../../api/useApiClient'
import { describeDownloadError, downloadSharedFile, searchFiles } from '../../api/fileDiscovery'
import type { RagFileItem, RagFileSearchParams, RagFileSearchResponse, RagSortKey } from '../../api/fileDiscovery'
import { listSources } from '../../api/sources'
import type { SourceResponse } from '../../api/sources'
import { ApiError } from '../../api/client'
import type { BlobResult } from '../../api/client'

/** Server default page size(`RagDiscoveryProperties.defaultPageSize`) - no page-size tuning UI (this slice's scope). */
const PAGE_SIZE = 20

/**
 * Exact-match MIME allowlist - the server never does wildcard/prefix matching
 * (M10 `RagFileSearchQuery.mimeType`), so this client never sends anything
 * the user typed freely for this field, only one of these known-exact values.
 */
const MIME_TYPE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '', label: '전체 형식' },
  { value: 'application/pdf', label: 'PDF' },
  { value: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', label: 'Word 문서(.docx)' },
  { value: 'text/plain', label: '텍스트(.txt)' },
  { value: 'text/markdown', label: 'Markdown(.md)' },
  { value: 'application/vnd.google-apps.document', label: 'Google 문서' },
  { value: 'image/png', label: 'PNG 이미지' },
  { value: 'image/jpeg', label: 'JPEG 이미지' },
  { value: 'video/mp4', label: 'MP4 동영상' },
  { value: 'application/zip', label: 'ZIP 압축 파일' },
]

const SORT_OPTIONS: Array<{ value: RagSortKey; label: string }> = [
  { value: 'MODIFIED_AT_DESC', label: '최근 수정순' },
  { value: 'MODIFIED_AT_ASC', label: '오래된 수정순' },
  { value: 'NAME_ASC', label: '이름 오름차순' },
  { value: 'NAME_DESC', label: '이름 내림차순' },
]

const INDEX_STATUS_LABELS: Record<string, string> = {
  PENDING: '색인 대기',
  INDEXED: '색인됨',
  SKIPPED_UNSUPPORTED: '색인 미지원 형식',
  SKIPPED_NO_TEXT: '텍스트 없음',
  FAILED: '색인 실패',
  STALE: '재색인 필요',
}

interface Criteria {
  q: string
  mimeType: string
  /** '' = every Source this account can search; else a specific owned Source id as a string. */
  sourceId: string
  /** `<input type="date">` value (`YYYY-MM-DD`) or ''. */
  modifiedFrom: string
  modifiedTo: string
  sort: RagSortKey
}

const EMPTY_CRITERIA: Criteria = {
  q: '',
  mimeType: '',
  sourceId: '',
  modifiedFrom: '',
  modifiedTo: '',
  sort: 'MODIFIED_AT_DESC',
}

type ResultState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'loaded'; response: RagFileSearchResponse }

type SourceOptionsState = { kind: 'idle' } | { kind: 'loaded'; sources: SourceResponse[] } | { kind: 'error' }

type DownloadState = { kind: 'downloading' } | { kind: 'error'; message: string }

/**
 * M16B - authenticated File Discovery over already-synced, Live-reverified
 * Metadata (`GET /api/rag/files`, M10 RAG-011). This is metadata search, not
 * content search/answers - there is intentionally no "Ask AI" action and this
 * page never fetches file content.
 *
 * <p>Search criteria and results live only in this component's own volatile
 * state - never the URL query string, `localStorage`/`IndexedDB`, a Service
 * Worker, a persisted store, or any log/analytics payload. Filenames/search
 * terms a user types must never leak into browser history.</p>
 */
export function FileDiscoveryPage() {
  const { isAdmin } = useAuth()
  const apiClient = useApiClient()

  const [draft, setDraft] = useState<Criteria>(EMPTY_CRITERIA)
  const [submitted, setSubmitted] = useState<Criteria>(EMPTY_CRITERIA)
  const [page, setPage] = useState(0)
  const [dateError, setDateError] = useState<string | null>(null)
  const [result, setResult] = useState<ResultState>({ kind: 'loading' })
  const [sourceOptions, setSourceOptions] = useState<SourceOptionsState>({ kind: 'idle' })
  // 검색 조건(submitted)이나 Page가 "값은 같지만 참조도 같은" 경우(예: 아무
  // 것도 바꾸지 않고 같은 조건으로 다시 시도, 또는 이미 0인 Page에서 다시
  // 검색) React는 State 변경 자체를 건너뛸 수 있어 아래 검색 Effect가 다시
  // 실행되지 않을 수 있다 - 특히 hasMore=null(불완전) 상태의 "같은 조건으로
  // 재시도" 요구사항과 정면으로 충돌한다. 그래서 실제 검색을 유발하는 모든
  // 사용자 Action(제출, 이전/다음)마다 이 값을 증가시켜 Effect가 항상 다시
  // 실행되도록 만든다 - 이 값 자체는 검색 조건에 포함되지 않는다.
  const [searchAttempt, setSearchAttempt] = useState(0)

  // M16C - shareId를 Key로 하는 다운로드 진행 상태. 같은 shareId로의 중복 제출을
  // 막고(동기 검사), 이 State를 통해 Row별 오류 메시지를 독립적으로 보여준다.
  const [downloads, setDownloads] = useState<Record<number, DownloadState>>({})
  // 진행 중인 요청을 Unmount/계정 전환 시 실제로 Abort하기 위한 Controller 보관소 -
  // React State가 아니다(Abort 자체는 화면을 다시 그릴 필요가 없는 부수 효과다).
  const downloadControllersRef = useRef(new Map<number, AbortController>())

  useEffect(() => {
    const controllers = downloadControllersRef.current
    return () => {
      // Unmount(로그아웃으로 전체 트리가 사라지거나, key={subject}로 계정이
      // 바뀌어 이 컴포넌트 자체가 다시 마운트되는 경우 포함) - 그 시점까지
      // 끝나지 않은 다운로드 요청을 모두 취소해, 이미 사라진 화면에 뒤늦게
      // Blob을 내려받아 저장 대화상자를 띄우는 일이 없게 한다.
      controllers.forEach((controller) => controller.abort())
      controllers.clear()
    }
  }, [])

  async function handleDownload(item: RagFileItem) {
    if (downloads[item.shareId]?.kind === 'downloading') {
      return
    }
    const controller = new AbortController()
    downloadControllersRef.current.set(item.shareId, controller)
    setDownloads((prev) => ({ ...prev, [item.shareId]: { kind: 'downloading' } }))
    try {
      const result = await downloadSharedFile(apiClient, item.shareId, controller.signal)
      triggerBrowserDownload(result, item.name)
      setDownloads((prev) => {
        const next = { ...prev }
        delete next[item.shareId]
        return next
      })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        return // 의도적으로 취소됨(Unmount 등) - 이미 사라진 화면에 오류를 표시하지 않는다.
      }
      setDownloads((prev) => ({ ...prev, [item.shareId]: { kind: 'error', message: describeDownloadError(error) } }))
    } finally {
      downloadControllersRef.current.delete(item.shareId)
    }
  }

  // ADMIN만 자신이 소유한 Source 목록으로 필터링할 수 있다 - 비Admin은 이
  // 목록 API 자체를 호출하지 않는다(서버가 허용하는 범위를 그대로 검색할 뿐,
  // 다른 사용자의 Source 이름/ID를 알아낼 방법을 만들지 않는다).
  useEffect(() => {
    if (!isAdmin) {
      return
    }
    let cancelled = false
    listSources(apiClient)
      .then((sources) => {
        if (cancelled) return
        setSourceOptions({ kind: 'loaded', sources: sources.filter((source) => source.status === 'ACTIVE') })
      })
      .catch(() => {
        if (cancelled) return
        setSourceOptions({ kind: 'error' })
      })
    return () => {
      cancelled = true
    }
  }, [apiClient, isAdmin])

  // 이 Effect 자체는 절대 동기적으로 setState하지 않는다(SourcesPage/HomePage와
  // 같은 관례) - 'loading' 표시는 항상 그것을 유발한 사용자 이벤트(검색 제출,
  // 이전/다음 Page 클릭)의 Handler에서 미리 설정한다. 여기서는 오직 실제
  // 비동기 응답(.then/.catch)에서만 State를 바꾼다.
  useEffect(() => {
    let cancelled = false
    searchFiles(apiClient, buildSearchParams(submitted, page))
      .then((response) => {
        if (cancelled) return
        setResult({ kind: 'loaded', response })
      })
      .catch((error: unknown) => {
        if (cancelled) return
        // 인증/권한 실패를 포함해, 이 'error' 상태 자체가 이전 'loaded' 결과를
        // 완전히 대체한다 - 낡은 결과를 화면에 남겨두지 않는다.
        setResult({ kind: 'error', message: describeSearchError(error) })
      })
    return () => {
      cancelled = true
    }
  }, [apiClient, submitted, page, searchAttempt])

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const validationError = validateDateRange(draft.modifiedFrom, draft.modifiedTo)
    if (validationError) {
      setDateError(validationError)
      return
    }
    setDateError(null)
    setResult({ kind: 'loading' })
    setSubmitted(draft)
    setPage(0) // 검색 조건이 바뀌면 Page를 반드시 0으로 되돌린다.
    setSearchAttempt((attempt) => attempt + 1)
  }

  function goToPreviousPage() {
    setResult({ kind: 'loading' })
    setPage((current) => Math.max(0, current - 1))
    setSearchAttempt((attempt) => attempt + 1)
  }

  function goToNextPage() {
    setResult({ kind: 'loading' })
    setPage((current) => current + 1)
    setSearchAttempt((attempt) => attempt + 1)
  }

  return (
    <>
      <h1>문서 찾기</h1>
      <p className="text-secondary">
        이미 동기화된 Metadata Catalog에서, 지금 이 순간 실제로 접근 가능한 파일만 찾습니다. 문서
        내용을 검색하거나 AI가 답변하는 기능이 아닙니다.
      </p>

      <form className="card" onSubmit={handleSubmit}>
        <label className="form-label" htmlFor="file-search-q">
          파일 이름
        </label>
        <div className="form-row">
          <input
            id="file-search-q"
            className="input"
            value={draft.q}
            onChange={(event) => setDraft((prev) => ({ ...prev, q: event.target.value }))}
            placeholder="이름의 일부를 입력하세요"
            maxLength={200}
          />
        </div>

        <label className="form-label" htmlFor="file-search-mime">
          형식
        </label>
        <div className="form-row">
          <select
            id="file-search-mime"
            className="input"
            value={draft.mimeType}
            onChange={(event) => setDraft((prev) => ({ ...prev, mimeType: event.target.value }))}
          >
            {MIME_TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        {isAdmin ? (
          <>
            <label className="form-label" htmlFor="file-search-source">
              Source
            </label>
            <div className="form-row">
              <select
                id="file-search-source"
                className="input"
                value={draft.sourceId}
                onChange={(event) => setDraft((prev) => ({ ...prev, sourceId: event.target.value }))}
              >
                <option value="">모든 연결된 Source</option>
                {sourceOptions.kind === 'loaded' &&
                  sourceOptions.sources.map((source) => (
                    <option key={source.id} value={source.id}>
                      {source.name}
                    </option>
                  ))}
              </select>
            </div>
            {sourceOptions.kind === 'error' && (
              <p className="text-secondary" style={{ margin: '4px 0 0' }}>
                Source 목록을 불러오지 못했습니다 - 모든 연결된 Source를 대상으로 검색합니다.
              </p>
            )}
          </>
        ) : (
          <p className="text-secondary" style={{ margin: '4px 0 0' }}>
            내가 접근할 수 있는 범위의 문서만 검색됩니다.
          </p>
        )}

        <label className="form-label" htmlFor="file-search-from">
          수정일 범위
        </label>
        <div className="form-row">
          <input
            id="file-search-from"
            type="date"
            className="input"
            aria-label="수정일 시작"
            value={draft.modifiedFrom}
            onChange={(event) => setDraft((prev) => ({ ...prev, modifiedFrom: event.target.value }))}
          />
          <input
            id="file-search-to"
            type="date"
            className="input"
            aria-label="수정일 종료"
            value={draft.modifiedTo}
            onChange={(event) => setDraft((prev) => ({ ...prev, modifiedTo: event.target.value }))}
          />
        </div>
        {dateError && <div className="status-banner status-banner--error form-row">{dateError}</div>}

        <label className="form-label" htmlFor="file-search-sort">
          정렬
        </label>
        <div className="form-row">
          <select
            id="file-search-sort"
            className="input"
            value={draft.sort}
            onChange={(event) => setDraft((prev) => ({ ...prev, sort: event.target.value as RagSortKey }))}
          >
            {SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <button type="submit" className="btn btn--primary">
            검색
          </button>
        </div>
      </form>

      <FileResultsPanel
        result={result}
        page={page}
        onPrevious={goToPreviousPage}
        onNext={goToNextPage}
        downloads={downloads}
        onDownload={handleDownload}
      />
    </>
  )
}

function FileResultsPanel({
  result,
  page,
  onPrevious,
  onNext,
  downloads,
  onDownload,
}: {
  result: ResultState
  page: number
  onPrevious: () => void
  onNext: () => void
  downloads: Record<number, DownloadState>
  onDownload: (item: RagFileItem) => void
}) {
  if (result.kind === 'loading') {
    return <div className="status-banner">검색 중입니다...</div>
  }
  if (result.kind === 'error') {
    return <div className="status-banner status-banner--error">{result.message}</div>
  }

  const { items, hasMore, partial } = result.response

  return (
    <>
      {items.length === 0 && !partial && <div className="status-banner">조건에 맞는 파일을 찾지 못했습니다.</div>}

      {items.length === 0 && partial && (
        <div className="status-banner status-banner--error">
          일부 결과를 확인하지 못해 지금은 보여드릴 파일이 없습니다("파일이 없다"는 뜻이 아닙니다) -
          조건을 좁히거나 같은 조건으로 다시 시도해 주세요.
        </div>
      )}

      {items.length > 0 && (
        <ul className="file-list">
          {items.map((item) => (
            <FileRow
              key={item.documentId}
              item={item}
              downloadState={downloads[item.shareId]}
              onDownload={() => onDownload(item)}
            />
          ))}
        </ul>
      )}

      {items.length > 0 && partial && (
        <div className="status-banner status-banner--error">
          일부 결과를 확인하지 못했습니다 - 위 목록이 이 Page의 전부가 아닐 수 있습니다. 같은 조건으로
          다시 시도하면 더 확인될 수 있습니다.
        </div>
      )}

      <div className="form-row">
        <button type="button" className="btn" onClick={onPrevious} disabled={page === 0}>
          이전
        </button>
        <button type="button" className="btn" onClick={onNext} disabled={hasMore !== true}>
          다음
        </button>
      </div>
      {hasMore === null && (
        <p className="text-secondary" style={{ margin: 0 }}>
          다음 Page가 더 있는지 확인하지 못했습니다 - "더 없다"는 뜻이 아닙니다.
        </p>
      )}
    </>
  )
}

function FileRow({
  item,
  downloadState,
  onDownload,
}: {
  item: RagFileItem
  downloadState?: DownloadState
  onDownload: () => void
}) {
  const link = safeDriveViewUrl(item.viewUrl)
  // 이 값은 힌트일 뿐이다 - 실제 인가 여부는 다운로드 Endpoint 자신이 매번 다시 결정한다.
  const canDownload = item.allowedActions.includes('DOWNLOAD')
  const downloading = downloadState?.kind === 'downloading'
  return (
    <li className="file-row">
      <div className="file-row__meta">
        <span className="file-row__name">{item.name}</span>
        <span className="text-secondary">
          {item.mimeType} · {formatModifiedAt(item.modifiedAt)}
        </span>
        <span className="text-secondary">{describeIndexStatus(item)}</span>
        {downloadState?.kind === 'error' && (
          <span className="status-banner status-banner--error">{downloadState.message}</span>
        )}
      </div>
      <div className="file-row__actions">
        {canDownload && (
          <button type="button" className="btn btn--primary" onClick={onDownload} disabled={downloading}>
            {downloading ? '다운로드 중...' : 'SDV 다운로드'}
          </button>
        )}
        {/* Google 원본 링크는 SDV 다운로드와 완전히 별개다 - 이 앱을 거치지 않고
            Google 자신의 권한 확인을 그대로 따른다(SDV 공유 인가와 무관). */}
        {link && (
          <a className="btn" href={link} target="_blank" rel="noopener noreferrer">
            Google에서 원본 열기
          </a>
        )}
      </div>
    </li>
  )
}

/**
 * M16C - Blob을 절대 HTML로 미리보기하거나 `localStorage`/`IndexedDB`/Service
 * Worker Cache에 남기지 않는다. 짧게 사는 Object URL 하나를 만들어 숨겨진
 * `<a download>`를 프로그램적으로 클릭시켜 사용자 컴퓨터로 내려받게 한 뒤,
 * 곧바로(다음 정리 시점에) 그 URL을 해제한다 - 이미 전달된 바이트 자체를
 * 되돌릴 방법은 없다(단지 이 탭이 그 URL을 더 오래 붙잡지 않을 뿐이다).
 */
function triggerBrowserDownload(result: BlobResult, fallbackName: string): void {
  const filename = safeDownloadFilename(result.filename) ?? safeDownloadFilename(fallbackName) ?? 'download'
  const url = URL.createObjectURL(result.blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

/** 방어적 재검증 - 서버가 이미 위험 문자를 걸러내지만, 이 값을 파일시스템 이름으로 직접 쓰기 전에 한 번 더 다듬는다. */
function safeDownloadFilename(candidate: string | null): string | null {
  if (!candidate) {
    return null
  }
  const cleaned = candidate.replace(/[\\/:*?"<>|\r\n]/g, '').trim()
  return cleaned.length > 0 ? cleaned : null
}

/**
 * 방어적 재검증 - 서버가 이미 `drive.google.com` 고정 URL만 보내지만, 이
 * Client는 그 값을 그대로 신뢰하지 않고 Scheme/Host를 다시 확인한 뒤에만
 * 실제 클릭 가능한 Link로 그린다. Google이 원본을 열 때 자신의 접근권한을
 * 다시 확인한다 - 이 Client가 "그 순간의 스냅샷이 고정됐다"고 약속하지 않는다.
 */
function safeDriveViewUrl(url: string): string | null {
  try {
    const parsed = new URL(url)
    if (parsed.protocol === 'https:' && parsed.hostname === 'drive.google.com') {
      return parsed.toString()
    }
  } catch {
    // Malformed URL - 아래에서 null을 반환해 Link 자체를 그리지 않는다.
  }
  return null
}

function describeIndexStatus(item: RagFileItem): string {
  const label = INDEX_STATUS_LABELS[item.indexStatus] ?? item.indexStatus
  if (item.indexStatus === 'INDEXED' && !item.sourceVersionCurrent) {
    return `${label} (오래된 버전일 수 있음 - 최신 파일 기준 재확인 필요)`
  }
  return label
}

function formatModifiedAt(modifiedAt: string | null): string {
  if (!modifiedAt) {
    return '수정 시각 확인 불가'
  }
  const parsed = new Date(modifiedAt)
  if (Number.isNaN(parsed.getTime())) {
    return '수정 시각 확인 불가'
  }
  return parsed.toLocaleString()
}

function buildSearchParams(criteria: Criteria, page: number): RagFileSearchParams {
  return {
    q: criteria.q.trim() || undefined,
    mimeType: criteria.mimeType || undefined,
    sourceId: criteria.sourceId ? Number(criteria.sourceId) : undefined,
    modifiedFrom: criteria.modifiedFrom ? startOfLocalDayInstant(criteria.modifiedFrom) : undefined,
    modifiedTo: criteria.modifiedTo ? endOfLocalDayInstant(criteria.modifiedTo) : undefined,
    sort: criteria.sort,
    page,
    size: PAGE_SIZE,
  }
}

/** `<input type="date">`의 `YYYY-MM-DD` 값을 "사용자 로컬 자정"으로 해석해 UTC Instant 문자열로 변환한다. */
function startOfLocalDayInstant(dateOnly: string): string {
  return new Date(`${dateOnly}T00:00:00`).toISOString()
}

/** 같은 날짜를 "사용자 로컬 하루의 끝"으로 해석한다 - 선택한 날짜 전체가 범위에 포함되도록. */
function endOfLocalDayInstant(dateOnly: string): string {
  return new Date(`${dateOnly}T23:59:59.999`).toISOString()
}

function validateDateRange(from: string, to: string): string | null {
  if (!from || !to) {
    return null
  }
  if (from > to) {
    return '시작일이 종료일보다 늦을 수 없습니다.'
  }
  return null
}

function describeSearchError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'AUTHENTICATION_REQUIRED':
        return '로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.'
      case 'ACCESS_DENIED':
        return '이 작업을 수행할 권한이 없습니다.'
      case 'VALIDATION_ERROR':
        return '검색 조건을 확인해 주세요.'
      case 'NETWORK_ERROR':
        return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
      default:
        return '검색 중 알 수 없는 오류가 발생했습니다.'
    }
  }
  return '검색 중 알 수 없는 오류가 발생했습니다.'
}
