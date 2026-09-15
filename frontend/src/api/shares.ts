import type { ApiClient } from './client'

/** Mirrors backend {@code com.sdv.policy.domain.SecurityLevel} - exact allowlist, do not invent other values. */
export type Classification = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'SECRET'

/**
 * Mirrors backend {@code com.sdv.source.domain.ShareAction}. `VIEW` only means
 * this file can be *found* by the recipient (common discovery) - it is not an
 * implemented "AI can answer from this file" permission (Mandatory Live
 * Retrieval/grounded answers are a separate, not-yet-delivered slice).
 * `DOWNLOAD` grants the SDV-authorized download endpoint, nothing else (no
 * native Google permission is granted or required for either action).
 */
export type ShareAction = 'VIEW' | 'DOWNLOAD'

export const CLASSIFICATION_OPTIONS: readonly Classification[] = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET']
export const SHARE_ACTION_OPTIONS: readonly ShareAction[] = ['VIEW', 'DOWNLOAD']

/** The backend's own hard cap (`SourceSharingService.MAX_RECIPIENTS`) - mirrored here only to fail fast client-side, not as the authority. */
export const MAX_RECIPIENTS = 20

/** Mirrors backend {@code ShareResponse} (`GET/POST /api/shares`, `PATCH /api/shares/{shareId}`) - the publisher's own view of one share. */
export interface ShareResponse {
  id: number
  sourceId: number
  documentId: number
  classification: Classification
  allowedActions: ShareAction[]
  recipients: string[]
  adminBlocked: boolean
  adminBlockReason: string | null
  generation: number
  active: boolean
  createdAt: string
  updatedAt: string
  revokedAt: string | null
}

/** Mirrors backend {@code AdminShareResponse} (`GET/PATCH /api/admin/shares`) - never carries the publisher's credential/private Drive listing. */
export interface AdminShareResponse {
  id: number
  publisherSubject: string
  sourceId: number
  documentId: number
  classification: Classification
  allowedActions: ShareAction[]
  recipients: string[]
  adminBlocked: boolean
  adminBlockReason: string | null
  generation: number
  createdAt: string
  updatedAt: string
}

export interface CreateShareInput {
  sourceId: number
  documentId: number
  classification: Classification
  actions: ShareAction[]
  recipients: string[]
}

export interface UpdateShareInput {
  expectedGeneration: number
  classification: Classification
  actions: ShareAction[]
  recipients: string[]
}

/**
 * `GET /api/shares` - the current publisher's own shares, including revoked
 * history (never another publisher's). `signal` lets a caller (e.g. the
 * retry/conflict-reload reconciliation in `ShareSettingsDialog`) cancel this
 * lookup if the operation it belongs to is no longer valid.
 */
export function listMyShares(client: ApiClient, signal?: AbortSignal): Promise<ShareResponse[]> {
  return client.get<ShareResponse[]>('/shares', signal)
}

/**
 * `POST /api/shares`. `signal` is forwarded all the way to the underlying
 * `fetch` - see {@link ApiClient.post} for the exact cancellation-timing
 * guarantee (rechecked immediately before dispatch, after token acquisition).
 */
export function createShare(client: ApiClient, input: CreateShareInput, signal?: AbortSignal): Promise<ShareResponse> {
  return client.post<ShareResponse>('/shares', input, signal)
}

/**
 * `PATCH /api/shares/{shareId}` - optimistic concurrency via `expectedGeneration`.
 * A `SHARE_GENERATION_CONFLICT` (409) means someone else's change (another of
 * the publisher's own tabs, or an ADMIN block) landed first - callers must
 * reload the current share and require a fresh confirmation, never blindly
 * retry with the same stale generation. `signal` - see {@link createShare}.
 */
export function updateShare(
  client: ApiClient,
  shareId: number,
  input: UpdateShareInput,
  signal?: AbortSignal,
): Promise<ShareResponse> {
  return client.patch<ShareResponse>(`/shares/${shareId}`, input, signal)
}

/** `DELETE /api/shares/{shareId}` - explicit unshare. Permanent; re-sharing needs a new {@link createShare} call. */
export function unshare(client: ApiClient, shareId: number): Promise<void> {
  return client.del(`/shares/${shareId}`)
}

// ------------------------------------------------------------------
// ADMIN-only shared-material management (`/api/admin/shares`) - policy/block
// only. Never exposes another owner's private Drive listing, and never grants
// ADMIN a way to expand a publisher's own recipients/actions/classification.
// ------------------------------------------------------------------

export function listAdminShares(client: ApiClient): Promise<AdminShareResponse[]> {
  return client.get<AdminShareResponse[]>('/admin/shares')
}

export function setShareBlocked(
  client: ApiClient,
  shareId: number,
  blocked: boolean,
  reason?: string,
): Promise<AdminShareResponse> {
  return client.patch<AdminShareResponse>(`/admin/shares/${shareId}`, { blocked, reason: reason ?? null })
}
