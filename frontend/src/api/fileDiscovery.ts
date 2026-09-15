import type { ApiClient, BlobResult } from './client'
import { ApiError } from './client'
import type { ShareAction } from './shares'

/** Mirrors backend {@code com.sdv.rag.api.dto.RagFileSortKey} - exact Allowlist, do not invent other values. */
export type RagSortKey = 'NAME_ASC' | 'NAME_DESC' | 'MODIFIED_AT_ASC' | 'MODIFIED_AT_DESC'

/**
 * Mirrors backend {@code RagFileItem}(M10). All fields are already Live
 * (Google) re-verified by the server at response time - this client never
 * re-derives or overrides them. `downloadable` is intentionally NOT surfaced
 * in the UI - it reflects Google `capabilities.canDownload`, which is not
 * proof that this app may read/answer from the file's content (M10 §2A.4/§2A.5).
 */
export interface RagFileItem {
  documentId: number
  sourceId: number
  name: string
  mimeType: string
  sourceVersion: string
  /** ISO-8601 Instant, or null if Google never reported a usable modified time for this generation. */
  modifiedAt: string | null
  /** {@code com.sdv.source.domain.DocumentIndexStatus} name - Content-processing status only, not "AI ready". */
  indexStatus: string
  /** {@code false} means the persisted `indexStatus` may describe an older content generation than this live version. */
  sourceVersionCurrent: boolean
  downloadable: boolean
  /** Always a validated `https://drive.google.com/...` URL, or absent - never rendered as raw HTML. */
  viewUrl: string
  /**
   * M16C - the exact share that authorized this item's exposure. NEVER the
   * same concept as `documentId` - do not guess/derive a shareId any other
   * way, and never call the publisher's own `/api/shares` management API as
   * the recipient. Pass this straight to `downloadSharedFile`/`GET
   * /api/shares/{shareId}/download` - that endpoint performs its own full
   * re-authorization regardless of what this field says.
   */
  shareId: number
  /**
   * A hint only, for deciding whether to offer a download action - the
   * download endpoint is the sole authority and re-checks this itself on
   * every call. A missing `DOWNLOAD` entry here must never be worked around
   * client-side.
   */
  allowedActions: ShareAction[]
}

/**
 * Mirrors backend {@code RagFileSearchResponse} exactly - `hasMore` is a
 * Tri-state (`true`/`false`/`null`), NOT a plain boolean. `null` together with
 * `partial=true` means "verification was incomplete", never "no more files" -
 * callers must not collapse it to `false`.
 */
export interface RagFileSearchResponse {
  items: RagFileItem[]
  hasMore: boolean | null
  partial: boolean
}

export interface RagFileSearchParams {
  q?: string
  mimeType?: string
  sourceId?: number
  /** ISO-8601 Instant strings - already converted from the user's local date boundary by the caller. */
  modifiedFrom?: string
  modifiedTo?: string
  sort: RagSortKey
  page: number
  size: number
}

/**
 * `GET /api/rag/files`(F-BE-095, M10 RAG-011). Every parameter is encoded via
 * {@link URLSearchParams} - never string-concatenated - so filter values
 * (including ones a user might type) cannot break the query string.
 */
export function searchFiles(client: ApiClient, params: RagFileSearchParams): Promise<RagFileSearchResponse> {
  const query = new URLSearchParams()
  if (params.q) query.set('q', params.q)
  if (params.mimeType) query.set('mimeType', params.mimeType)
  if (params.sourceId !== undefined) query.set('sourceId', String(params.sourceId))
  if (params.modifiedFrom) query.set('modifiedFrom', params.modifiedFrom)
  if (params.modifiedTo) query.set('modifiedTo', params.modifiedTo)
  query.set('sort', params.sort)
  query.set('page', String(params.page))
  query.set('size', String(params.size))
  return client.get<RagFileSearchResponse>(`/rag/files?${query.toString()}`)
}

/**
 * `GET /api/shares/{shareId}/download`(SHR-004) - B's SDV-authorized download
 * of a file A published, with no native Google permission or Google
 * connection of B's own. Binary-only: a non-2xx response is always the
 * backend's fixed JSON error shape and is thrown as {@link ApiError}, never
 * handed back as file bytes. Pass `signal` so an in-flight download can be
 * aborted on unmount/logout/account switch - see {@link ApiClient.getBlob}.
 */
export function downloadSharedFile(client: ApiClient, shareId: number, signal?: AbortSignal): Promise<BlobResult> {
  return client.getBlob(`/shares/${shareId}/download`, signal)
}

/** Backend `SharedFileDownloadException.reason()` names, mirrored as the `SHARED_DOWNLOAD_<reason>` {@link ApiError.code} values. */
export type SharedDownloadFailureCode =
  | 'SHARED_DOWNLOAD_NOT_AUTHORIZED'
  | 'SHARED_DOWNLOAD_NOT_AVAILABLE'
  | 'SHARED_DOWNLOAD_DOCUMENT_CHANGED'
  | 'SHARED_DOWNLOAD_EXPORT_LIMIT_EXCEEDED'
  | 'SHARED_DOWNLOAD_FILE_TOO_LARGE'
  | 'SHARED_DOWNLOAD_REQUEST_TIMEOUT'
  | 'SHARED_DOWNLOAD_CAPACITY_EXHAUSTED'

/**
 * A polite, honest Korean message per distinct failure - never a single
 * generic "다운로드 실패" for every case, so the user knows whether retrying
 * now is likely to help.
 */
export function describeDownloadError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code as SharedDownloadFailureCode | string) {
      case 'AUTHENTICATION_REQUIRED':
        return '로그인 인증을 확인하지 못했습니다. 다시 로그인해 주세요.'
      case 'SHARED_DOWNLOAD_NOT_AUTHORIZED':
      case 'SHARED_DOWNLOAD_NOT_AVAILABLE':
        return '이 파일을 다운로드할 권한이 없거나, 더 이상 사용할 수 없습니다.'
      case 'SHARED_DOWNLOAD_DOCUMENT_CHANGED':
        return '원본 문서가 변경되어 다시 확인이 필요합니다. 다시 시도해 주세요.'
      case 'SHARED_DOWNLOAD_EXPORT_LIMIT_EXCEEDED':
      case 'SHARED_DOWNLOAD_FILE_TOO_LARGE':
        return '파일이 너무 크거나 내보내기 한도를 초과해 다운로드할 수 없습니다.'
      case 'SHARED_DOWNLOAD_REQUEST_TIMEOUT':
        return '다운로드 요청이 시간 초과됐습니다. 잠시 후 다시 시도해 주세요.'
      case 'SHARED_DOWNLOAD_CAPACITY_EXHAUSTED':
        return '지금은 동시 다운로드 용량이 가득 찼습니다. 잠시 후 다시 시도해 주세요.'
      case 'NETWORK_ERROR':
        return '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
      default:
        return '다운로드 중 알 수 없는 오류가 발생했습니다.'
    }
  }
  return '다운로드 중 알 수 없는 오류가 발생했습니다.'
}
