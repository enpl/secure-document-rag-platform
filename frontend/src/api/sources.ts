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
