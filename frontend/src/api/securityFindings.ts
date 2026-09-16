import type { ApiClient } from './client'
export type FindingStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'
export interface SecurityFinding { id: number; type: string; severity: string; status: FindingStatus; sourceId: number | null; documentId: number | null; evidence: Record<string, string>; detectedAt: string }
export interface FindingPage { items: SecurityFinding[]; page: number; size: number; hasMore: boolean }
export function listFindings(client: ApiClient, page = 0, signal?: AbortSignal): Promise<FindingPage> {
  return client.get(`/admin/security/findings?page=${page}&size=50`, signal)
}
export function updateFinding(client: ApiClient, id: number, status: FindingStatus, signal?: AbortSignal): Promise<SecurityFinding> {
  return client.patch(`/admin/security/findings/${id}`, { status }, signal)
}
