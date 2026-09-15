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
