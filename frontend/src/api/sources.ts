import type { ApiClient } from './client'

/** {@code com.sdv.source.domain.SourceType} - SHAREPOINT/S3 exist server-side as Contract/Skeleton only (v1.4). */
export type SourceType = 'GOOGLE_DRIVE' | 'LOCAL_VAULT' | 'SHAREPOINT' | 'S3'

/** Mirrors {@code SourceConnection.STATUS_ACTIVE}/{@code STATUS_DISABLED} - kept as the backend's own string contract. */
export type SourceStatus = 'ACTIVE' | 'DISABLED' | string

/** Mirrors backend {@code SourceResponse} (MVP-17: adds `name`/`credentialPresent`). */
export interface SourceResponse {
  id: number
  type: SourceType
  name: string
  status: SourceStatus
  lastSyncAt: string | null
  /**
   * A local encrypted Google credential row exists for this Source. This is
   * NOT proof that the credential is still valid at Google - only that SDV
   * has not yet had a reason to require reconnecting.
   */
  credentialPresent: boolean
}

export interface GoogleAuthorizeResponse {
  authorizationUrl: string
}

/** Only value the backend's own tests exercise for {@code CreateSourceRequest.syncMode} - not invented. */
export const GOOGLE_DRIVE_SYNC_MODE = 'FULL'

export function listSources(client: ApiClient): Promise<SourceResponse[]> {
  return client.get<SourceResponse[]>('/admin/sources')
}

export function createGoogleDriveSource(client: ApiClient, name: string): Promise<SourceResponse> {
  return client.post<SourceResponse>('/admin/sources', {
    type: 'GOOGLE_DRIVE' satisfies SourceType,
    name,
    syncMode: GOOGLE_DRIVE_SYNC_MODE,
  })
}

export function disconnectSource(client: ApiClient, id: number): Promise<void> {
  return client.del(`/admin/sources/${id}`)
}

export function authorizeGoogleSource(client: ApiClient, sourceId: number): Promise<GoogleAuthorizeResponse> {
  return client.get<GoogleAuthorizeResponse>(`/admin/sources/google/authorize?sourceId=${sourceId}`)
}

/**
 * Mirrors backend {@code SyncRunResponse}(F-BE-062, `POST
 * /api/admin/sources/{id}/sync`) - a Metadata/ACL Catalog Sync run, not
 * Content indexing. `total`/`success`/`failed` are sync *processing* counts
 * (a document seen more than once across pages could count more than once) -
 * not necessarily unique files, and not "documents made AI-answerable".
 */
export interface SyncRunResponse {
  runId: number
  sourceId: number
  mode: string
  /** One of the backend's own {@code SyncRunEntity.STATUS_*} strings (e.g. COMPLETED/PARTIAL_FAILURE/FAILED/ABANDONED/RUNNING) - kept as its string contract. */
  status: string
  total: number
  success: number
  failed: number
  startedAt: string
  endedAt: string | null
}

/**
 * Triggers one manual Metadata/ACL Catalog sync for a Source this admin owns.
 * The call is synchronous - it does not return until the backend has finished
 * (or failed) the run. There is no job/progress API to poll, so callers must
 * show a single indeterminate waiting state and must never invent a
 * percentage or auto-retry on their own.
 */
export function syncSource(client: ApiClient, sourceId: number): Promise<SyncRunResponse> {
  return client.post<SyncRunResponse>(`/admin/sources/${sourceId}/sync`, undefined)
}

// ------------------------------------------------------------------
// M16C - owner-scoped "내 Drive" API (`SourceUserController`, `/api/sources`).
// Any authenticated USER or ADMIN acting as an owner uses these - they are a
// separate endpoint family from the ADMIN-only `/api/admin/sources/**` above,
// even though both ultimately call the same `SourceConnectionService`/
// `SourceSyncService` (owner-scoped either way). Never mix the two base paths.
// ------------------------------------------------------------------

/** Mirrors backend {@code SourceFileResponse} (`GET /api/sources/{id}/files`) - a private-picker row, not a shared/discoverable one. */
export interface SourceFileResponse {
  documentId: number
  name: string
  mimeType: string
  modifiedAt: string | null
  indexStatus: string
}

/** Mirrors backend {@code SourceFilesPageResponse} - a plain boolean `hasMore` (unlike RAG discovery's tri-state), no raw total/cursor. */
export interface SourceFilesPageResponse {
  items: SourceFileResponse[]
  hasMore: boolean
}

export function listMyDriveSources(client: ApiClient): Promise<SourceResponse[]> {
  return client.get<SourceResponse[]>('/sources')
}

export function createMyGoogleDriveSource(client: ApiClient, name: string): Promise<SourceResponse> {
  return client.post<SourceResponse>('/sources', {
    type: 'GOOGLE_DRIVE' satisfies SourceType,
    name,
    syncMode: GOOGLE_DRIVE_SYNC_MODE,
  })
}

export function disconnectMyDriveSource(client: ApiClient, id: number): Promise<void> {
  return client.del(`/sources/${id}`)
}

export function syncMyDriveSource(client: ApiClient, sourceId: number): Promise<SyncRunResponse> {
  return client.post<SyncRunResponse>(`/sources/${sourceId}/sync`, undefined)
}

/**
 * Honest bounded pagination over the owner's own private catalog - there is
 * no folder hierarchy in this response (flat list only); a full Drive
 * crawler/synthetic folder tree is explicitly out of this slice's scope.
 * Never call this for a Source the current user does not own - the backend
 * independently re-checks ownership regardless.
 */
export function listMyDriveFiles(
  client: ApiClient,
  sourceId: number,
  page: number,
  size: number,
): Promise<SourceFilesPageResponse> {
  return client.get<SourceFilesPageResponse>(`/sources/${sourceId}/files?page=${page}&size=${size}`)
}
