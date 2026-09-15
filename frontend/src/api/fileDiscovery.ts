import type { ApiClient } from './client'

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
